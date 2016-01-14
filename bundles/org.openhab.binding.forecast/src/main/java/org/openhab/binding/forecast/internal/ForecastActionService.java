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
package org.openhab.binding.forecast.internal;

import org.openhab.binding.forecast.ForecastAction;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.model.script.engine.action.ActionService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Andreas Gebauer
 */
@Component(service = { ForecastActionService.class, ActionService.class })
public class ForecastActionService implements ActionService {

    @Activate
    public ForecastActionService(@Reference ItemRegistry itemRegistry) {
        ForecastAction.setService(this);
        ForecastAction.setItemRegistry(itemRegistry);
    }

    @Override
    public String getActionClassName() {
        return ForecastAction.class.getCanonicalName();
    }

    @Override
    public Class<?> getActionClass() {
        return ForecastAction.class;
    }
}
