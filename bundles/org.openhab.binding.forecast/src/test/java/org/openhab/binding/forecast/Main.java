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

import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;

import net.sourceforge.openforecast.DataSet;
import net.sourceforge.openforecast.Observation;
import net.sourceforge.openforecast.models.MultipleLinearRegressionModel;

/**
 * @author Andreas Gebauer
 */
public class Main {

    public static void main(String[] args) {

        NumberItem item = new NumberItem("item");
        item.setState(new DecimalType(0.0d));
        String model = "net.sourceforge.openforecast.models.MultipleLinearRegressionModel";

        DataSet dataSet = new DataSet();

        int numberOfValues = 40;

        for (int i = 0; i < numberOfValues; i++) {
            dataSet.add(new Observation(20.0));
        }

        ForecastAction.doForecast(dataSet, 60 * 40, 120, new MultipleLinearRegressionModel());
    }
}
