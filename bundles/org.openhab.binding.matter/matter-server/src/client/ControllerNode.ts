// Include this first to auto-register Crypto, Network and Time Node.js implementations
import { Environment, Logger, ObserverGroup, SharedEnvironmentServices, StorageContext, StorageService } from "@matter/general";
import { NodeId } from "@matter/types";
import { CommissioningController } from "@project-chip/matter.js";
import { Endpoint, NodeStates, PairedNode } from "@project-chip/matter.js/device";
import { WebSocketSession } from "../app";
import { EventType, NodeState } from "../MessageTypes";
import { printError } from "../util/error";
import { ControllerBehavior, SoftwareUpdateManager } from "@matter/node";
import { DclOtaUpdateService, PhysicalDeviceProperties } from "@matter/main/protocol";

const logger = Logger.get("ControllerNode");

// Attributes that are always read fresh from the device, even when the rest of the node is served from the
// local subscription cache (requestFromRemote=false). softwareVersion/softwareVersionString only change on a
// firmware update, so after a device reboots onto new firmware the cached value can be stale - and it is
// surfaced as the Thing's firmware-version property, so it must be accurate. The cost is one tiny read of a
// rarely-changing attribute per serialization, with a fallback to the cached value if the read fails.
const ALWAYS_FRESH_ATTRIBUTES: Record<string, ReadonlySet<string>> = {
    BasicInformation: new Set(["softwareVersion", "softwareVersionString"]),
    BridgedDeviceBasicInformation: new Set(["softwareVersion", "softwareVersionString"]),
};

function extractPhysicalProperties(node: PairedNode | undefined): PhysicalDeviceProperties | undefined {
    if (!node) return undefined;
    try {
        const deviceInformation = node.deviceInformation;
        if (!deviceInformation) return undefined;
        // these are lazy properties, so we need to access them to actually hydrate our return object
        return { ...deviceInformation };
    } catch (e) {
        logger.debug(`Could not read deviceInformation for node ${node.nodeId}: ${e}`);
        return undefined;
    }
}

/**
 * This class represents the Matter Controller / Admin client
 */
export class ControllerNode {
    private environment: Environment = Environment.default;
    private storageContext?: StorageContext;
    private nodes: Map<NodeId, PairedNode> = new Map();
    // Per-node observer groups so listeners can be removed in bulk before re-registering,
    // avoiding duplicate handlers (and duplicate WebSocket events) when the same node instance
    // is reused across reconnections.
    private nodeObservers: Map<NodeId, ObserverGroup> = new Map();
    // Wedge self-heal: per-node timestamp (ms) of the last subscription liveness signal.
    // PairedNode `connectionAlive` fires on every subscription update, including the periodic
    // maxInterval keep-alive, so it is the true "subscription is alive" signal (independent of
    // whether any attribute actually changed). Crucially it does NOT fire on bootstrap/reconnect
    // reads (matter.js NetworkClient guards it behind an active subscriptionId), so it is immune to
    // the read-back bursts a reconnect produces - a real "is the live subscription delivering?"
    // signal. If a node stays Connected but stops signalling, matter.js's own resubscribe
    // (InteractionClient updateTimeoutHandler) has failed to fire - observed with IKEA sleepy ICD
    // sensors (MYGGSPRAY/MYGGBETT): the device stays reachable and keeps sending UDP 5540 packets to
    // the host and updating other fabrics (Google/Alexa) while openHAB receives nothing.
    //
    // Recovery escalation (proven necessary 2026-06-19): a per-node triggerReconnect does NOT clear
    // a *hard* wedge - the openHAB binding's "controller restart" (Thing disable/enable) only bounces
    // the websocket to this same, persistent Node.js process, so the wedged matter.js engine survives
    // it. Measured: a 2.5 h wedge survived 3 triggerReconnects + 3 controller restarts while the
    // devices sent 60+ packets/15 min. The only thing that clears it is restarting this Node process.
    // So the watchdog first tries the light triggerReconnect, and if the subscription is STILL silent
    // a grace window later, it calls process.exit(1): MatterWebsocketService.waitFor() sees the exit
    // and respawns a fresh matter.js engine (~10 s), which re-subscribes cleanly.
    private nodeLastAlive: Map<NodeId, number> = new Map();
    private nodeLastReconnect: Map<NodeId, number> = new Map();
    // Timestamp (ms) of the last time a node re-entered `Connected` state. matter.js only reports
    // Connected from `#handleSubscriptionStatusChanged(true)` / `#handleSubscriptionAlive()`, i.e.
    // once a subscription is actually active - so a Connected transition *after* our forced
    // reconnect is positive proof that the resubscribe was accepted and the matter.js engine is
    // processing inbound traffic. That is the difference between "the device is quiet" and "the
    // engine is wedged", and it must gate the process restart (see startWedgeWatchdog).
    private nodeLastResubscribe: Map<NodeId, number> = new Map();
    // Consecutive wedge episodes in which the forced resubscribe succeeded but the subscription
    // still delivered no report within a full WEDGE_TIMEOUT_MS window. Reset on `connectionAlive`.
    private nodeFutileResubscribes: Map<NodeId, number> = new Map();
    private wedgeWatchdog?: NodeJS.Timeout;
    // No subscription update within this window on a still-Connected node => possibly wedged. MUST
    // exceed the negotiated subscription maxInterval plus margin, or the watchdog tears down healthy
    // subscriptions on a timer and never lets the keep-alive arrive. These IKEA sensors negotiate
    // `interval: 30m timeout: 30m 38s` (matter.js `Subscription successful` TRACE line), so the old
    // 20 min - chosen from the ~15m39s figure in matterjs-server #526 - was BELOW the keep-alive
    // cadence and guaranteed a permanent reconnect/restart loop on any quiet device (observed
    // 2026-08-21: ~150 fires/day, matter-server killed every ~25 min around the clock).
    // Tunable; watch the matter.js log for `connectionAlive` cadence to refine.
    private static readonly WEDGE_TIMEOUT_MS = 40 * 60 * 1000;
    // Do not re-trigger a reconnect for the same node more often than this.
    private static readonly WEDGE_RECONNECT_COOLDOWN_MS = 10 * 60 * 1000;
    // After triggering the light reconnect, allow this long for `connectionAlive` to resume before
    // considering the process restart. Sized for an awake device's CASE re-handshake + first
    // subscription report. NB this alone does not prove a wedge: a *successful* resubscribe to a
    // quiet sleepy device legitimately delivers nothing for up to the negotiated maxInterval, which
    // is why the restart is additionally gated on nodeLastResubscribe.
    private static readonly WEDGE_RESTART_GRACE_MS = 4 * 60 * 1000;
    // How many consecutive episodes of "resubscribe succeeded, yet still no report a full
    // WEDGE_TIMEOUT_MS later" to tolerate before treating it as a hard wedge after all. Keeps the
    // process-restart safety net for the case where matter.js accepts the subscription but never
    // delivers, without letting a merely-quiet device trigger it.
    private static readonly WEDGE_MAX_FUTILE_RESUBSCRIBES = 2;
    private static readonly WEDGE_CHECK_INTERVAL_MS = 60 * 1000;
    commissioningController?: CommissioningController;
    private observers?: ObserverGroup;
    #services?: SharedEnvironmentServices;
    constructor(
        private readonly storageLocation: string,
        private readonly controllerName: string,
        private readonly nodeNum: number,
        public readonly ws: WebSocketSession,
        private readonly netInterface?: string,
    ) {}

    get Store() {
        if (!this.storageContext) {
            throw new Error("Storage uninitialized");
        }
        return this.storageContext;
    }

    get otaService() {
        if (!this.environment.has(DclOtaUpdateService)) {
            new DclOtaUpdateService(this.environment); // Adds itself to the environment
        }
        return this.services.get(DclOtaUpdateService);
    }

    protected get services() {
        if (!this.#services) {
            this.#services = this.environment.asDependent();
        }
        return this.#services;
    }

    /**
     * Closes the controller node
     */
    async close() {
        if (this.wedgeWatchdog) {
            clearInterval(this.wedgeWatchdog);
            this.wedgeWatchdog = undefined;
        }
        try {
            for (const observers of this.nodeObservers.values()) {
                observers.close();
            }
            this.nodeObservers.clear();
            this.observers?.close();
            await this.commissioningController?.close();
        } finally {
            // In a finally so the OTA blob storage lock is released (avoiding a leak that blocks the next
            // startup) even if the cleanup above throws.
            await this.#services?.close();
            this.#services = undefined;
            this.nodes.clear();
            this.nodeLastAlive.clear();
            this.nodeLastReconnect.clear();
            this.nodeLastResubscribe.clear();
            this.nodeFutileResubscribes.clear();
        }
    }

    /**
     * Disposes any existing observer group for the given node and returns a fresh one.
     * This guarantees that re-registering listeners for the same node instance (e.g. across
     * reconnections) does not accumulate duplicate handlers.
     */
    private resetNodeObservers(nodeId: NodeId): ObserverGroup {
        this.disposeNodeObservers(nodeId);
        const observers = new ObserverGroup();
        this.nodeObservers.set(nodeId, observers);
        return observers;
    }

    /**
     * Removes all listeners registered through the observer group for the given node, if any.
     */
    private disposeNodeObservers(nodeId: NodeId): void {
        const observers = this.nodeObservers.get(nodeId);
        if (observers !== undefined) {
            observers.close();
            this.nodeObservers.delete(nodeId);
        }
    }

    /**
     * Initializes the controller node
     */
    async initialize() {
        const outputDir = this.storageLocation;
        const id = `${this.controllerName}-${this.nodeNum.toString()}`;
        const prefix = "openHAB: ";
        const fabricLabel = prefix + this.controllerName.substring(0, 31 - prefix.length);

        logger.info(`Storage location: ${outputDir} (Directory)`);
        this.environment.vars.set("storage.path", outputDir);

        // TODO we may need to choose which network interface to use
        if (this.netInterface !== undefined) {
            this.environment.vars.set("mdns.networkinterface", this.netInterface);
        }
        this.commissioningController = new CommissioningController({
            environment: {
                environment: this.environment,
                id,
            },
            autoConnect: false,
            adminFabricLabel: fabricLabel,
            enableOtaProvider: true
        });
        
        const storageService = this.commissioningController.env.get(StorageService);
        // TODO: Implement resetStorage
        // if (resetStorage) {
        //     await this.commissioningController.node.erase();
        // }
        this.storageContext = (await storageService.open(id)).createContext("Node");


        if (await this.Store.has("ControllerFabricLabel")) {
            await this.commissioningController.updateFabricLabel(
                await this.Store.get<string>("ControllerFabricLabel", fabricLabel),
            );
        }

        await this.commissioningController.start();

        // matter.js 0.17 defaults to sequential operational node IDs starting at NodeId(1). The counter is not
        // restored consistently with the fabric across restarts, so it re-attempts low IDs that are already
        // commissioned ("Node ID X is already commissioned and can not be reused"). Switch to the documented
        // random allocation strategy (CHANGELOG: set ControllerBehavior state nodeIdAssignment to "random").
        await this.commissioningController.node.setStateOf(ControllerBehavior, { nodeIdAssignment: "random" });

        //Set up observers for OTA updates, matter.js checks every 24 hours by default.
        this.observers = this.observers ?? new ObserverGroup(this.environment.runtime);
        const updateManagerEvents = this.commissioningController.otaProvider.eventsOf(SoftwareUpdateManager);
        this.observers.on(updateManagerEvents.updateAvailable, (peer, details) => {
            logger.info(`Update available for peer `, peer, `:`, details);
            const nodeId = peer?.nodeId;
            if(!nodeId) {
                logger.error(`Node ID not found for peer `, peer);
                return;
            }
            this.ws.sendEvent(EventType.UpdateAvailable, {
                nodeId: nodeId.valueOf(),
                ...details,
            });
        });
        this.observers.on(updateManagerEvents.updateDone, peer => {
            logger.info(`Update done for peer `, peer);
            const nodeId = peer?.nodeId;
            if (!nodeId) {
                logger.error(`Node ID not found for peer `, peer);
                return;
            }
            // matter.js only emits updateDone once the device has already rebooted onto the new firmware and
            // re-established its session, so it is reachable now and we refresh immediately. The serialized node
            // (and thus the Thing's firmware-version property) is normally built from the local subscription cache
            // (requestFromRemote=false), which may still hold the pre-update version until the subscription has
            // re-primed; forcing a fresh remote read makes the reported version reflect the firmware just installed,
            // rebuilds the structure/channels and brings the Thing (set OFFLINE while applying) back ONLINE in one
            // step. If the device should be briefly unreachable again, sendSerializedNode triggers a reconnect on
            // failure and ALWAYS_FRESH_ATTRIBUTES corrects the version on the next serialization. OTA is rare, so a
            // one-off full read is cheap.
            try {
                this.sendSerializedNode(this.getNode(nodeId), undefined, true);
            } catch (e) {
                logger.error(`Could not refresh node ${nodeId} after OTA update: ${e}`);
            }
        });

        // Query for updates now once
        const updates = await this.commissioningController.otaProvider.act(agent =>
            agent.get(SoftwareUpdateManager).queryUpdates()
        );
        if (updates && updates.length > 0) {
            for (const update of updates) {
                logger.info(`Update available for peer `, update.peerAddress, `:`, update.info);
                this.ws.sendEvent(EventType.UpdateAvailable, {
                    nodeId: update.peerAddress.nodeId.valueOf(),
                    ...update.info,
                });
            }
        }

        this.startWedgeWatchdog();
    }

    /**
     * Wedge self-heal watchdog. Periodically checks every Connected node for a stalled subscription
     * (no `connectionAlive` within WEDGE_TIMEOUT_MS). Two-stage recovery:
     *   1. Light reconnect (triggerReconnect) - rebuilds the CASE session + subscription in-process.
     *      Clears transient stalls cheaply, without disturbing the other nodes or the controller.
     *   2. WEDGE_RESTART_GRACE_MS later, if `connectionAlive` is still silent AND the forced
     *      resubscribe never completed (the node did not re-enter Connected), the wedge is hard: the
     *      matter.js engine itself is stuck and a reconnect/websocket bounce cannot clear it
     *      (confirmed 2026-06-19). Escalate to process.exit(1); MatterWebsocketService respawns a
     *      fresh Node process and re-subscribes all nodes cleanly. This is the in-process equivalent
     *      of the openHAB-side node-PID-kill backstop, but faster and keyed off the reliable
     *      `connectionAlive` signal (immune to reconnect read-back bursts).
     * If the resubscribe DID complete, the engine is provably processing inbound traffic and the
     * silence just means the sleepy device has nothing to report yet, so the node is granted a fresh
     * WEDGE_TIMEOUT_MS window instead of a process restart. Only after
     * WEDGE_MAX_FUTILE_RESUBSCRIBES such episodes back-to-back - a subscription that is repeatedly
     * accepted yet never delivers - does it escalate anyway. Without that gate the watchdog kills a
     * healthy engine every WEDGE_TIMEOUT_MS + grace forever (2026-08-21 regression).
     */
    private startWedgeWatchdog() {
        if (this.wedgeWatchdog) {
            clearInterval(this.wedgeWatchdog);
        }
        this.wedgeWatchdog = setInterval(() => {
            const now = Date.now();
            for (const [nodeId, node] of this.nodes) {
                if (node.connectionState !== NodeStates.Connected) {
                    continue;
                }
                const lastAlive = this.nodeLastAlive.get(nodeId) ?? 0;
                if (lastAlive === 0 || now - lastAlive < ControllerNode.WEDGE_TIMEOUT_MS) {
                    continue;
                }
                const staleMin = Math.round((now - lastAlive) / 60000);
                const lastReconnect = this.nodeLastReconnect.get(nodeId) ?? 0;

                // Stage 2: a light reconnect was already attempted and the subscription is still
                // silent a grace window later. Before calling that a hard wedge, check whether the
                // resubscribe actually succeeded: if the node re-entered Connected after the
                // reconnect, matter.js negotiated a fresh subscription and is demonstrably
                // processing inbound traffic, so the engine is NOT wedged - the device is simply
                // quiet, and killing the process here would destroy every CASE session and
                // subscription for nothing (the 2026-08-21 kill loop).
                if (lastReconnect !== 0 && now - lastReconnect >= ControllerNode.WEDGE_RESTART_GRACE_MS) {
                    const lastResubscribe = this.nodeLastResubscribe.get(nodeId) ?? 0;
                    if (lastResubscribe >= lastReconnect) {
                        const futile = (this.nodeFutileResubscribes.get(nodeId) ?? 0) + 1;
                        this.nodeFutileResubscribes.set(nodeId, futile);
                        if (futile < ControllerNode.WEDGE_MAX_FUTILE_RESUBSCRIBES) {
                            logger.warn(
                                `Wedge watchdog: node ${nodeId} resubscribed successfully after the forced reconnect but has delivered no report for ${staleMin} min (episode ${futile}/${ControllerNode.WEDGE_MAX_FUTILE_RESUBSCRIBES}) - the engine is alive, so not restarting it; granting another full window`,
                            );
                            // Fresh window: the new subscription's keep-alive is due within its
                            // negotiated maxInterval, which WEDGE_TIMEOUT_MS now sits above.
                            this.nodeLastAlive.set(nodeId, now);
                            this.nodeLastReconnect.delete(nodeId);
                            continue;
                        }
                        logger.warn(
                            `Wedge watchdog: node ${nodeId} has now resubscribed successfully ${futile}x without ever delivering a report - treating as a hard wedge after all`,
                        );
                    }
                    logger.warn(
                        `Wedge watchdog: node ${nodeId} still silent ${staleMin} min after reconnect - matter.js engine is wedged, restarting the matter-server process (process.exit) so the binding respawns a clean one`,
                    );
                    // Flush logs before the process dies, then exit non-zero so the binding's
                    // waitFor()/scheduledStart() path respawns us (vs a clean SHUTTING_DOWN shutdown).
                    process.exitCode = 1;
                    process.exit(1);
                }

                // Stage 1: first detection of this wedge episode (or the reconnect cooldown lapsed) =>
                // try the light in-process reconnect before escalating.
                if (lastReconnect === 0 || now - lastReconnect >= ControllerNode.WEDGE_RECONNECT_COOLDOWN_MS) {
                    logger.warn(
                        `Wedge watchdog: node ${nodeId} Connected but no subscription update for ${staleMin} min - forcing reconnect/resubscribe`,
                    );
                    this.nodeLastReconnect.set(nodeId, now);
                    try {
                        node.triggerReconnect();
                    } catch (e) {
                        logger.error(`Wedge watchdog: triggerReconnect failed for node ${nodeId}: ${e}`);
                    }
                }
            }
        }, ControllerNode.WEDGE_CHECK_INTERVAL_MS);
        // Do not keep the Node.js event loop alive solely for this timer.
        this.wedgeWatchdog.unref?.();
    }

    /**
     * Connects to a node, setting up event listeners. If called multiple times for the same node, it will trigger a node reconnect.
     * If a connection timeout is provided, the function will return a promise that will resolve when the node is initialized or reject if the node
     * becomes disconnected or the timeout is reached. Note that the node will continue to connect in the background and the client will be notified
     * when the node is initialized through the NodeStateInformation event. To stop the reconnection, call the disconnectNode method.
     *
     * @param nodeId  The nodeId of the node to connect to
     * @param connectionTimeout Optional timeout in milliseconds. If omitted or non-positive, no timeout will be applied
     * @returns Promise that resolves when the node is initialized
     * @throws Error if connection times out or node becomes disconnected
     */
    async initializeNode(nodeId: string | number, connectionTimeout?: number): Promise<void> {
        if (this.commissioningController === undefined) {
            throw new Error("CommissioningController not initialized");
        }

        let node = this.nodes.get(NodeId(BigInt(nodeId)));
        if (node !== undefined) {
            // We are already connected so there is nothing to reconnect. Send the connected state so the
            // client refreshes, otherwise we would wait for an init event that never fires again and time out.
            if (node.connectionState === NodeStates.Connected) {
                this.ws.sendEvent(EventType.NodeStateInformation, {
                    nodeId: node.nodeId,
                    state: NodeStates[NodeStates.Connected],
                    physicalProperties: extractPhysicalProperties(node),
                });
                return;
            }

            node.triggerReconnect();

            return new Promise((resolve, reject) => {
                let timeoutId: NodeJS.Timeout | undefined;

                // initializedFromRemote only fires on the very first connect of a PairedNode instance; a later
                // reconnect re-asserts the Connected state but never emits it again. So we also resolve on a live
                // stateChanged -> Connected transition, otherwise an already-initialized node that has to reconnect
                // here would wait for an event that never comes and run into the timeout (Thing flips OFFLINE).
                const onStateChanged = (state: NodeStates): void => {
                    if (state === NodeStates.Connected) {
                        finish("reconnected");
                    }
                };
                const finish = (reason: string): void => {
                    if (timeoutId) clearTimeout(timeoutId);
                    node?.events.initializedFromRemote.off(onInitialized);
                    node?.events.stateChanged.off(onStateChanged);
                    logger.info(`Node ${node?.nodeId} ${reason}`);
                    // Send a connected event so the client knows the resume completed
                    this.ws.sendEvent(EventType.NodeStateInformation, {
                        nodeId: node!.nodeId,
                        state: NodeStates[NodeStates.Connected],
                        physicalProperties: extractPhysicalProperties(node),
                    });
                    resolve();
                };
                const onInitialized = (): void => finish("initialized from remote");

                if (connectionTimeout && connectionTimeout > 0) {
                    timeoutId = setTimeout(() => {
                        node?.events.initializedFromRemote.off(onInitialized);
                        node?.events.stateChanged.off(onStateChanged);
                        logger.info(`Node ${node?.nodeId} state: ${node?.state}`);
                        if (
                            node?.connectionState === NodeStates.Disconnected ||
                            node?.connectionState === NodeStates.WaitingForDeviceDiscovery ||
                            node?.connectionState === NodeStates.Reconnecting
                        ) {
                            reject(new Error(`Node ${node?.nodeId} reconnection failed: ${NodeStates[node?.connectionState]}`));
                        } else {
                            reject(new Error(`Node ${node?.nodeId} reconnection timed out`));
                        }
                    }, connectionTimeout);
                }

                // The node may already be Connected again by the time we get here (between the guard above and
                // triggerReconnect), so check once before waiting for an event.
                if (node?.connectionState === NodeStates.Connected) {
                    finish("already connected");
                    return;
                }

                node?.events.initializedFromRemote.once(onInitialized);
                node?.events.stateChanged.on(onStateChanged);
            });
        }

        node = await this.commissioningController.getNode(NodeId(BigInt(nodeId)));
        if (node === undefined) {
            throw new Error(`Node ${nodeId} not connected`);
        }
        this.nodes.set(node.nodeId, node);

        // Remove any listeners left over from a previous registration of this same node instance
        // (e.g. after a decommission/re-commission cycle) so handlers do not accumulate.
        const observers = this.resetNodeObservers(node.nodeId);

        // Track subscription liveness for the wedge self-heal watchdog. Seed it now so a freshly
        // registered node gets a full WEDGE_TIMEOUT_MS grace before it can be considered wedged.
        this.nodeLastAlive.set(node.nodeId, Date.now());
        observers.on(node.events.connectionAlive, () => {
            this.nodeLastAlive.set(node!.nodeId, Date.now());
            // Liveness resumed: clear any in-progress wedge escalation so the next episode starts
            // fresh with the light reconnect before escalating to a process restart.
            this.nodeLastReconnect.delete(node!.nodeId);
            this.nodeFutileResubscribes.delete(node!.nodeId);
            logger.debug(`connectionAlive node ${node!.nodeId}`);
        });

        observers.on(node.events.stateChanged, info => {
            if (info === NodeStates.Connected) {
                // matter.js reports Connected only once a subscription is active, so this doubles as
                // the "resubscribe accepted" signal the wedge watchdog needs to distinguish a quiet
                // device from a wedged engine.
                this.nodeLastResubscribe.set(node!.nodeId, Date.now());
            }
            this.ws.sendEvent(EventType.NodeStateInformation, {
                nodeId: node!.nodeId,
                state: NodeStates[info],
            });
        });

        observers.on(node.events.structureChanged, () => {
            this.ws.sendEvent(EventType.NodeStateInformation, {
                nodeId: node!.nodeId,
                state: NodeState.STRUCTURE_CHANGED,
            });
        });

        observers.on(node.events.decommissioned, () => {
            this.disposeNodeObservers(node!.nodeId);
            this.nodes.delete(node!.nodeId);
            this.ws.sendEvent(EventType.NodeStateInformation, {
                nodeId: node!.nodeId,
                state: NodeState.DECOMMISSIONED,
            });
        });

        // attributeChanged and eventTriggered only need to be wired up once initialization completes,
        // to avoid forwarding the init state updates as user visible updates. Use once() so they are
        // wired exactly once per registration; the inner handlers are tracked by the observer group
        // (reset above) so they are still removed in bulk on re-registration or decommission.
        // Wire up the user-visible event forwarding and emit the connected event once the node has completed
        // its remote initialization.
        const handleInitialized = () => {
            observers.on(node!.events.attributeChanged, data => {
                data.path.nodeId = node!.nodeId;
                this.ws.sendEvent(EventType.AttributeChanged, data);
            });

            observers.on(node!.events.eventTriggered, data => {
                data.path.nodeId = node!.nodeId;
                this.ws.sendEvent(EventType.EventTriggered, data);
            });

            // send a connected event in case the stateChanged transition
            // to connected was missed despite the early listener attachment above.
            this.ws.sendEvent(EventType.NodeStateInformation, {
                nodeId: node!.nodeId,
                state: NodeStates[NodeStates.Connected],
                physicalProperties: extractPhysicalProperties(node),
            });
        };

        // matter.js auto-connects a node immediately after commissioning, so by the time we get here the
        // initializedFromRemote event may have already fired and will not fire again. In that case finish
        // synchronously instead of waiting for an event that never comes - otherwise initializeNode runs into
        // its timeout after a successful commissioning and the new node never surfaces to the client.
        if (node.remoteInitializationDone) {
            handleInitialized();
            return;
        }

        node.events.initializedFromRemote.once(handleInitialized);

        node.connect();

        return new Promise((resolve, reject) => {
            let timeoutId: NodeJS.Timeout | undefined;

            if (connectionTimeout && connectionTimeout > 0) {
                timeoutId = setTimeout(() => {
                    logger.info(`Node ${node?.nodeId} initialization timed out`);

                    if (
                        node?.connectionState === NodeStates.Disconnected ||
                        node?.connectionState === NodeStates.WaitingForDeviceDiscovery
                    ) {
                        reject(new Error(`Node ${node.nodeId} connection failed: ${NodeStates[node.connectionState]}`));
                    } else {
                        reject(new Error(`Node ${node!.nodeId} connection timed out`));
                    }
                }, connectionTimeout);
            }

            node!.events.initializedFromRemote.once(() => {
                if (timeoutId) clearTimeout(timeoutId);
                resolve();
            });
        });
    }

    /**
     * Returns a node by nodeId.  If the node has not been initialized, it will throw an error.
     * @param nodeId
     * @returns
     */
    getNode(nodeId: number | string | NodeId) {
        if (this.commissioningController === undefined) {
            throw new Error("CommissioningController not initialized");
        }
        const node = this.nodes.get(NodeId(BigInt(nodeId)));
        if (node === undefined) {
            throw new Error(`Node ${nodeId} not connected`);
        }
        return node;
    }

    /**
     * Removes a node from the controller
     * @param nodeId
     */
    async removeNode(nodeId: number | string | NodeId) {
        const node = this.nodes.get(NodeId(BigInt(nodeId)));
        if (node !== undefined) {
            try {
                await node.decommission();
            } catch (error) {
                logger.error(`Error decommissioning node ${nodeId}: ${error} force removing node`);
                await this.commissioningController?.removeNode(NodeId(BigInt(nodeId)), false);
                this.disposeNodeObservers(node.nodeId);
                this.nodes.delete(NodeId(BigInt(nodeId)));
            }
        } else {
            await this.commissioningController?.removeNode(NodeId(BigInt(nodeId)), false);
        }
    }

    /**
     * Returns all commissioned nodes Ids
     * @returns
     */
    async getCommissionedNodes() {
        return this.commissioningController?.getCommissionedNodes();
    }

    /**
     * Finds the given endpoint, included nested endpoints
     * @param node
     * @param endpointId
     * @returns
     */
    getEndpoint(node: PairedNode, endpointId: number) {
        const endpoints = node.getDevices();
        for (const e of endpoints) {
            const endpoint = this.findEndpoint(e, endpointId);
            if (endpoint != undefined) {
                return endpoint;
            }
        }
        return undefined;
    }

    /**
     *
     * @param root Endpoints can have child endpoints. This function recursively searches for the endpoint with the given id.
     * @param endpointId
     * @returns
     */
    private findEndpoint(root: Endpoint, endpointId: number): Endpoint | undefined {
        if (root.number === endpointId) {
            return root;
        }
        for (const endpoint of root.getChildEndpoints()) {
            const found = this.findEndpoint(endpoint, endpointId);
            if (found !== undefined) {
                return found;
            }
        }
        return undefined;
    }

    /**
     * Serializes a node and sends it to the web socket
     * @param node
     * @param endpointId Optional endpointId to serialize. If omitted, all endpoints will be serialized.
     * @param requestFromRemote When false (the default) values are taken from the local subscription cache instead of
     *  reading every attribute individually from the device. Since the node is subscribed (autoSubscribe) the cache is
     *  continuously kept up to date by the device, so a remote read per attribute is redundant and very expensive on
     *  slow Thread meshes. Pass true only to force a fresh remote read of every attribute.
     */
    sendSerializedNode(node: PairedNode, endpointId?: number, requestFromRemote: boolean = false) {
        this.serializePairedNode(node, endpointId, requestFromRemote)
            .then(data => {
                this.ws.sendEvent(EventType.NodeData, data);
            })
            .catch(error => {
                logger.error(`Error serializing node: ${error}`);
                printError(logger, error, "serializePairedNode");
                node.triggerReconnect();
            });
    }

    /**
     * Serializes a node and returns the json object
     * @param node
     * @param endpointId Optional endpointId to serialize. If omitted, the root endpoint will be serialized.
     * @param requestFromRemote When false (the default) attribute values are served from the local subscription cache.
     *  Reading every attribute remotely (true) issues one network request per attribute and is extremely slow for
     *  Thread / sleepy devices; the autoSubscribe cache already holds the current values. Fabric-scoped attributes are
     *  always read from the device regardless of this flag (enforced by matter.js).
     * @returns
     */
    async serializePairedNode(node: PairedNode, endpointId?: number, requestFromRemote: boolean = false) {
        if (!this.commissioningController) {
            throw new Error("CommissioningController not initialized");
        }

        // Recursive function to build the hierarchy
        async function serializeEndpoint(endpoint: Endpoint): Promise<any> {
            const endpointData: any = {
                number: endpoint.number,
                clusters: {},
                children: [],
            };

            // Serialize clusters
            for (const cluster of endpoint.getAllClusterClients()) {
                if (!cluster.id) continue;

                const clusterData: any = {
                    id: cluster.id,
                    name: cluster.name,
                };

                // Serialize attributes
                for (const attributeName in cluster.attributes) {
                    // Skip numeric referenced attributes
                    if (/^\d+$/.test(attributeName)) continue;
                    const attribute = cluster.attributes[attributeName];
                    if (!attribute) continue;
                    // Force a fresh remote read for attributes that must never be served stale from the cache
                    // (see ALWAYS_FRESH_ATTRIBUTES), but fall back to the cached value if that read fails so one
                    // flaky read of a single attribute never fails the whole node serialization.
                    const forceFresh =
                        !requestFromRemote && (ALWAYS_FRESH_ATTRIBUTES[cluster.name]?.has(attributeName) ?? false);
                    let attributeValue: any;
                    if (forceFresh) {
                        try {
                            attributeValue = await attribute.get(true);
                        } catch (e) {
                            logger.debug(`Fresh read of ${cluster.name}.${attributeName} failed, using cache: ${e}`);
                            try {
                                attributeValue = await attribute.get(false);
                            } catch (e2) {
                                logger.debug(
                                    `Cache read of ${cluster.name}.${attributeName} also failed, skipping: ${e2}`,
                                );
                                attributeValue = undefined;
                            }
                        }
                    } else {
                        attributeValue = await attribute.get(requestFromRemote);
                    }
                    logger.debug(`Attribute ${attributeName} value: ${attributeValue}`);
                    if (attributeValue !== undefined) {
                        clusterData[attributeName] = attributeValue;
                    }
                }

                endpointData.clusters[cluster.name] = clusterData;
            }

            for (const child of endpoint.getChildEndpoints()) {
                endpointData.children.push(await serializeEndpoint(child));
            }

            return endpointData;
        }

        // Start serialization from the root endpoint
        const rootEndpoint = endpointId !== undefined ? this.getEndpoint(node, endpointId) : node.getRootEndpoint();
        if (rootEndpoint === undefined) {
            throw new Error(`Endpoint not found for node ${node.nodeId} and endpointId ${endpointId}`);
        }
        const data: any = {
            id: node.nodeId,
            rootEndpoint: await serializeEndpoint(rootEndpoint),
        };

        return data;
    }
}
