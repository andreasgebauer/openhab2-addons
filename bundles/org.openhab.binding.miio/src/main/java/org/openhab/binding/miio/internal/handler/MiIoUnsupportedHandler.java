/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.miio.internal.handler;

import static org.openhab.binding.miio.internal.MiIoBindingConstants.*;

import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.miio.internal.basic.MiIoDatabaseWatchService;
import org.openhab.core.cache.ExpiringCache;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MiIoUnsupportedHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marcel Verpaalen - Initial contribution
 */
@NonNullByDefault
public class MiIoUnsupportedHandler extends MiIoAbstractHandler {
    private final Logger logger = LoggerFactory.getLogger(MiIoUnsupportedHandler.class);

    private final ExpiringCache<Boolean> updateDataCache = new ExpiringCache<>(CACHE_EXPIRY, () -> {
        scheduler.schedule(this::updateData, 0, TimeUnit.SECONDS);
        return true;
    });

    public MiIoUnsupportedHandler(Thing thing, MiIoDatabaseWatchService miIoDatabaseWatchService) {
        super(thing, miIoDatabaseWatchService);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            if (updateDataCache.isExpired()) {
                logger.debug("Refreshing {}", channelUID);
                updateDataCache.getValue();
            } else {
                logger.debug("Refresh {} skipped. Already refreshing", channelUID);
            }
            return;
        }
        if (channelUID.getId().equals(CHANNEL_POWER)) {
            if (command.equals(OnOffType.ON)) {
                sendCommand("set_power[\"on\"]");
            } else {
                sendCommand("set_power[\"off\"]");
            }
        }
        if (channelUID.getId().equals(CHANNEL_COMMAND)) {
            cmds.put(sendCommand(command.toString()), command.toString());
        }
        if (channelUID.getId().equals(CHANNEL_TESTCOMMANDS)) {
            executeExperimentalCommands();
        }
    }

    // TODO: In future version this ideally would test all known commands (e.g. from the database) and create/enable a
    // channel if they appear to be supported
    private void executeExperimentalCommands() {
        String[] testCommands = new String[0];
        switch (miDevice) {
            case POWERPLUG:
            case POWERPLUG2:
            case POWERSTRIP:
            case POWERSTRIP2:
            case YEELIGHT_C1:
                break;
            case VACUUM:
                testCommands = new String[] { "miIO.info", "get_current_sound", "get_map_v1", "get_serial_number",
                        "get_timezone" };
                break;
            case AIR_PURIFIERM:
            case AIR_PURIFIER1:
            case AIR_PURIFIER2:
            case AIR_PURIFIER3:
            case AIR_PURIFIER6:
                break;

            default:
                testCommands = new String[] { "miIO.info" };
                break;
        }
        logger.info("Start Experimental Testing of commands for device '{}'. ", miDevice.toString());
        for (String c : testCommands) {
            logger.info("Test command '{}'. Response: '{}'", c, sendCommand(c));
        }
    }

    @Override
    protected synchronized void updateData() {
        if (skipUpdate()) {
            return;
        }
        logger.debug("Periodic update for '{}' ({})", getThing().getUID().toString(), getThing().getThingTypeUID());
        try {
            refreshNetwork();
        } catch (Exception e) {
            logger.debug("Error while updating '{}' ({})", getThing().getUID().toString(), getThing().getThingTypeUID(),
                    e);
        }
    }
}