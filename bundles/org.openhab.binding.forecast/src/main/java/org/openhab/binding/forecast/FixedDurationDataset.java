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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Iterator;

import net.sourceforge.openforecast.DataPoint;
import net.sourceforge.openforecast.DataSet;
import net.sourceforge.openforecast.Observation;

/**
 * @author Andreas Gebauer
 */
public class FixedDurationDataset extends DataSet {

    private Duration duration;

    public FixedDurationDataset(Duration duration) {
        this.duration = duration;
    }

    @Override
    public boolean add(DataPoint obj) {
        this.removeObsoleteData();
        return super.add(obj);
    }

    /**
     * Adds a value with time now.
     * 
     * @param value
     */
    public void add(BigDecimal value) {
        Observation obj = new Observation(value.doubleValue());
        obj.setIndependentValue("timestamp", ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond());
        this.add(obj);
    }

    private void removeObsoleteData() {
        ZonedDateTime start = getStart();

        Iterator<DataPoint> iterator = super.iterator();

        while (iterator.hasNext()) {
            DataPoint next = iterator.next();

            int timeMillis = (int) next.getIndependentValue("timestamp");
            if (start.toEpochSecond() > timeMillis) {
                iterator.remove();
            }
        }
    }

    private ZonedDateTime getStart() {
        return ZonedDateTime.now(ZoneOffset.UTC).minus(duration);
    }
}
