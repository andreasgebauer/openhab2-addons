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

/**
 * @author Andreas Gebauer
 */
public class DataPoint {

    private long timestamp;
    private double value;

    public DataPoint(net.sourceforge.openforecast.DataPoint dataPoint) {
        this.timestamp = (long) dataPoint.getIndependentValue("timestamp");
        this.value = dataPoint.getDependentValue();
    }

    public double getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "DataPoint [timestamp=" + timestamp + ", value=" + value + "]";
    }
}
