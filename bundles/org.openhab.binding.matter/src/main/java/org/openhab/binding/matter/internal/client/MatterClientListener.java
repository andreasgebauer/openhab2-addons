/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.matter.internal.client;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.matter.internal.client.dto.ws.AttributeChangedMessage;
import org.openhab.binding.matter.internal.client.dto.ws.BridgeEventMessage;
import org.openhab.binding.matter.internal.client.dto.ws.EventTriggeredMessage;
import org.openhab.binding.matter.internal.client.dto.ws.NodeDataMessage;
import org.openhab.binding.matter.internal.client.dto.ws.NodeStateMessage;

/**
 * A listener for Matter client events
 *
 * @author Dan Cunningham - Initial contribution
 */
@NonNullByDefault
public interface MatterClientListener {
    public void onDisconnect(String reason);

    public void onConnect();

    public void onReady();

    public void onEvent(NodeStateMessage message);

    public void onEvent(AttributeChangedMessage message);

    public void onEvent(EventTriggeredMessage message);

    public void onEvent(BridgeEventMessage message);

    public void onEvent(NodeDataMessage message);

    /**
     * A subscription keep-alive event arrived for a node — emitted on every matter.js
     * connectionAlive (every subscription update or maxInterval keepalive). Used as
     * the canonical "subscription is healthy" signal for diagnostics.
     */
    public default void onSubscriptionAlive(java.math.BigInteger nodeId) {
    }
}
