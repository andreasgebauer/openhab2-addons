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

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 * @author Andreas Gebauer
 */
public class DataSet extends AbstractCollection<DataPoint> {

    private Collection<DataPoint> dataPoints = new ArrayList<DataPoint>();

    public DataSet(net.sourceforge.openforecast.DataSet dataSet) {
        for (net.sourceforge.openforecast.DataPoint dataPoint : dataSet) {
            dataPoints.add(new DataPoint(dataPoint));
        }
    }

    @Override
    public Iterator<DataPoint> iterator() {
        return dataPoints.iterator();
    }

    @Override
    public int size() {
        return dataPoints.size();
    }

    @Override
    public String toString() {
        return dataPoints.toString();
    }
}
