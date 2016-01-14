/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.forecast;

import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.items.Item;
import org.openhab.core.persistence.HistoricItem;

/**
 * @author Andreas Gebauer
 */
public interface HistoricStateAdapter {

    @Nullable
    HistoricItem stateOn(Item item, ZonedDateTime tsThen);
}
