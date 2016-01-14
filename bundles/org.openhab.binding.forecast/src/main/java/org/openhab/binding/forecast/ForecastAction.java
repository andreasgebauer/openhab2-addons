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

import static java.time.temporal.ChronoUnit.MILLIS;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.ClassUtils;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.forecast.internal.ForecastActionService;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.model.script.engine.action.ActionDoc;
import org.openhab.core.model.script.engine.action.ParamDoc;
import org.openhab.core.persistence.HistoricItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.openforecast.EvaluationCriteria;
import net.sourceforge.openforecast.Forecaster;
import net.sourceforge.openforecast.ForecastingModel;
import net.sourceforge.openforecast.Observation;

/**
 * @author Andreas Gebauer
 */
public class ForecastAction {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastAction.class);

    private static BigDecimal THOUSAND = new BigDecimal(1000);

    private static ItemRegistry itemRegistry;

    @ActionDoc(text = "Create a forecasting model")
    public static ForecastingModel createModel(String className, Object[] params) throws RuntimeException {
        Class<?> modelClass;
        try {
            modelClass = ForecastAction.class.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            try {
                modelClass = Thread.currentThread().getContextClassLoader().loadClass(className);
            } catch (ClassNotFoundException e2) {
                try {
                    modelClass = ForecastActionService.class.getClassLoader().loadClass(className);
                } catch (ClassNotFoundException e3) {
                    throw new RuntimeException("Unable to load class", e3);
                }
            }
        }

        try {
            Constructor<?>[] constructors = modelClass.getConstructors();
            conLoop: for (Constructor<?> con : constructors) {
                Class<?>[] conParams = con.getParameterTypes();
                if (params == null && conParams.length == 0) {
                    LOG.debug("No constructor params given. using constructor {}", con);
                    return (ForecastingModel) con.newInstance();
                } else if (conParams.length == params.length) {
                    LOG.debug("Parameter lengths fit");
                    for (int i = 0; i < conParams.length; i++) {
                        Class<?> conParam = conParams[i];
                        Object givenParam = params[i];
                        LOG.debug("Checking whether constructor param {} fits constructor param {}", givenParam,
                                conParam);
                        if (!ClassUtils.isAssignable(givenParam.getClass(), conParam, true)) {
                            LOG.debug("Given constructor param {} does not fit constructor param {}", givenParam,
                                    conParam);
                            continue conLoop;
                        }
                    }
                    LOG.debug("Will use constructor {}", con);
                    return (ForecastingModel) con.newInstance(params);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        throw new IllegalArgumentException(
                "No constructor found for class " + className + " and params " + Arrays.asList(params));
    }

    @ActionDoc(text = "make forecast")
    public static List<Double> forecast(String item, int secondsTotal, int numberOfValues, int numberOfValuesForecast) {
        int secondsBetween = getMillisBetweenSamples(secondsTotal, numberOfValues);
        net.sourceforge.openforecast.DataSet historicalDataSet = historicalDataSet(item, secondsBetween, numberOfValues,
                PersistenceStateAdapter.instance());

        return createNumberList(forecastWithCriteria(historicalDataSet, secondsBetween, numberOfValuesForecast, null));
    }

    @ActionDoc(text = "make forecast with criteria")
    public static List<Double> forecastWithCriteria(@ParamDoc(name = "", text = "") String item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") String criteriaSring) {

        int millis = getMillisBetweenSamples(secondsTotal, numberOfValues);

        net.sourceforge.openforecast.DataSet dataSet = historicalDataSet(item, millis, numberOfValues,
                PersistenceStateAdapter.instance());

        EvaluationCriteria criteria = null;
        if (criteriaSring != null) {
            try {
                criteria = (EvaluationCriteria) EvaluationCriteria.class.getField(criteriaSring)
                        .get(EvaluationCriteria.class);

            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        return createNumberList(forecastWithCriteria(dataSet, millis, numberOfValuesForecast, criteria));
    }

    @ActionDoc(text = "make forecast with model")
    public static DataSet forecastWithModel(@ParamDoc(name = "", text = "") String item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") String modelString, Object... params) {
        ForecastingModel model = createModel(modelString, params);
        return forecast(item, secondsTotal, numberOfValues, numberOfValuesForecast, model);
    }

    @ActionDoc(text = "make forecast with model")
    public static DataSet forecast(@ParamDoc(name = "", text = "") String item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") ForecastingModel model) {
        int millis = getMillisBetweenSamples(secondsTotal, numberOfValues);

        net.sourceforge.openforecast.DataSet dataSet = historicalDataSet(item, millis, numberOfValues,
                PersistenceStateAdapter.instance());

        return doForecast(dataSet, millis, numberOfValuesForecast, model);
    }

    @ActionDoc(text = "make forecast with model")
    public static DataSet forecast(@ParamDoc(name = "", text = "") Item item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") ForecastingModel model) {
        LOG.debug("forecast with item {} and model {}", item, model);

        int millis = getMillisBetweenSamples(secondsTotal, numberOfValues);

        LOG.trace("millis between: {}", millis);

        net.sourceforge.openforecast.DataSet dataSet = historicalDataSet(item, millis, numberOfValues,
                PersistenceStateAdapter.instance());
        LOG.trace("calculated historic dataset {}", dataSet);

        return doForecast(dataSet, millis, numberOfValuesForecast, model);
    }

    @ActionDoc(text = "make forecast with model and return number list")
    public static List<Double> forecastWithModelAsNumberList(@ParamDoc(name = "", text = "") Item item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") ForecastingModel model) {
        LOG.debug("Forecasting with item {} and model {}", item, model);
        return createNumberList(forecast(item, secondsTotal, numberOfValues, numberOfValuesForecast, model));
    }

    @ActionDoc(text = "make forecast with model and return number list")
    public static List<Double> forecastWithModelAsNumberList(@ParamDoc(name = "item", text = "") Item item,
            @ParamDoc(name = "", text = "") int secondsTotal, @ParamDoc(name = "", text = "") int numberOfValues,
            @ParamDoc(name = "", text = "") int numberOfValuesForecast,
            @ParamDoc(name = "", text = "") String modelString, @ParamDoc(name = "", text = "") Object[] params) {
        LOG.debug("Forecasting with item {} and model named {}", item, modelString);
        ForecastingModel model = createModel(modelString, params);
        return forecastWithModelAsNumberList(item, secondsTotal, numberOfValues, numberOfValuesForecast, model);
    }

    @ActionDoc(text = "forecastDataset")
    public static net.sourceforge.openforecast.DataSet forecastDataSet() {
        return new net.sourceforge.openforecast.DataSet();
    }

    @ActionDoc(text = "forecastObservation")
    public static net.sourceforge.openforecast.Observation forecastObservation(double dependentValue) {
        return new net.sourceforge.openforecast.Observation(dependentValue);
    }

    public static net.sourceforge.openforecast.DataSet historicalDataSet(String itemName, int millisBetween,
            int numberOfValues, HistoricStateAdapter adapter) {
        @Nullable
        Item ite = itemRegistry.get(itemName);
        if (ite == null) {
            throw new IllegalArgumentException("Could not find item with name " + itemName);
        }
        if (!(ite instanceof NumberItem)) {
            throw new IllegalArgumentException(
                    "Item " + itemName + " does not have required type NumberItem but is of type " + ite.getType());
        }
        return historicalDataSet(ite, millisBetween, numberOfValues, adapter);
    }

    public static net.sourceforge.openforecast.DataSet historicalDataSet(Item item, int millisBetween,
            int numberOfValues, HistoricStateAdapter adapter) {
        var dataSet = new net.sourceforge.openforecast.DataSet();
        dataSet.setTimeVariable("timestamp");
        var now = ZonedDateTime.now();
        dataSet.add(getObservation(item.getStateAs(DecimalType.class), now, 0));
        for (int i = 1; i <= numberOfValues; i++) {
            ZonedDateTime tsThen = now.minus(new BigDecimal(millisBetween).multiply(new BigDecimal(i)).intValue(),
                    MILLIS);
            HistoricItem historicState = adapter.stateOn(item, tsThen);
            LOG.trace("got historic state {} for {}", historicState, tsThen);
            if (historicState != null) {
                var state = historicState.getState();
                LOG.trace("historic state state is {}", state);
                ZonedDateTime timestamp = historicState.getTimestamp();
                dataSet.add(getObservation(state.as(DecimalType.class), timestamp, -i));
            }
        }

        return dataSet;
    }

    @ActionDoc(text = "do forecast with map")
    public static List<Double> doForecastWithMap(Map<Long, BigDecimal> data, int millisBetween,
            int numberOfValuesForecast, String modelName, Object[] params) {
        net.sourceforge.openforecast.DataSet dataset = new net.sourceforge.openforecast.DataSet();
        dataset.setTimeVariable("timestamp");

        int i = 0;
        for (Entry<Long, BigDecimal> dataPoint : data.entrySet()) {
            ZonedDateTime zdt = Instant.ofEpochMilli(dataPoint.getKey()).atZone(ZoneOffset.UTC);
            dataset.add(createObservation(zdt, i++, dataPoint.getValue().doubleValue()));
        }

        return createNumberList(
                doForecast(dataset, millisBetween, numberOfValuesForecast, createModel(modelName, params)));
    }

    @ActionDoc(text = "do forecast with ZonedDateTime map")
    public static List<Double> doForecastWithZonedDateTimeMap(Map<ZonedDateTime, BigDecimal> data, int millisBetween,
            int numberOfValuesForecast, String modelName, Object[] params) {
        net.sourceforge.openforecast.DataSet dataset = new net.sourceforge.openforecast.DataSet();
        dataset.setTimeVariable("timestamp");

        int i = 0;
        for (Entry<ZonedDateTime, BigDecimal> dataPoint : data.entrySet()) {
            ZonedDateTime zdt = dataPoint.getKey();
            dataset.add(createObservation(zdt, i++, dataPoint.getValue().doubleValue()));
        }

        return createNumberList(
                doForecast(dataset, millisBetween, numberOfValuesForecast, createModel(modelName, params)));
    }

    public static DataSet doForecast(net.sourceforge.openforecast.DataSet dataSet, int millisBetween,
            int numberOfValuesForecast, ForecastingModel forecastingModel) {
        LOG.debug("doForecast: ms between: {}, # of values: {}, model: {}", millisBetween, numberOfValuesForecast,
                forecastingModel);

        forecastingModel.init(dataSet);

        LOG.debug("Using forecast model {}", forecastingModel);

        return new DataSet(forecastingModel.forecast(createForecastDataSet(millisBetween, numberOfValuesForecast)));
    }

    public static List<Double> createNumberList(DataSet dataSet) {
        return dataSet.stream().map(t -> t.getValue()).toList();
    }

    // private

    private static DataSet forecastWithCriteria(net.sourceforge.openforecast.DataSet dataSet, int millisBetween,
            int numberOfValuesForecast, EvaluationCriteria criteria) {
        ForecastingModel forecastModel;
        if (criteria != null)
            forecastModel = Forecaster.getBestForecast(dataSet, criteria);
        else
            forecastModel = Forecaster.getBestForecast(dataSet);

        return doForecast(dataSet, millisBetween, numberOfValuesForecast, forecastModel);
    }

    private static net.sourceforge.openforecast.DataSet createForecastDataSet(int millisBetween,
            int numberOfValuesForecast) {
        ZonedDateTime now = ZonedDateTime.now();

        net.sourceforge.openforecast.DataSet fcDataset = new net.sourceforge.openforecast.DataSet();
        for (int i = 0; i < numberOfValuesForecast; i++) {
            Observation observation = new Observation(0.0);
            ZonedDateTime tsThen = now.plus(new BigDecimal(millisBetween).multiply(new BigDecimal(i + 1)).intValue(),
                    MILLIS);
            observation.setIndependentValue("timestamp", tsThen.toEpochSecond() * 1000);
            observation.setIndependentValue("number", i + 1);
            fcDataset.add(observation);
        }
        return fcDataset;
    }

    private static int getMillisBetweenSamples(int secondsTotal, int numberOfValues) {
        return new BigDecimal(secondsTotal).divide(new BigDecimal(numberOfValues)).multiply(THOUSAND).intValue();
    }

    private static Observation getObservation(DecimalType decimal, ZonedDateTime date, int index) {
        double doubleValue = decimal.doubleValue();

        return createObservation(date, index, doubleValue);
    }

    private static Observation createObservation(ZonedDateTime date, int index, double doubleValue) {
        Observation observation = new Observation(doubleValue);
        observation.setIndependentValue("timestamp", date.toEpochSecond() * 1000);
        observation.setIndependentValue("number", index);
        return observation;
    }

    public static void setService(ForecastActionService forecastActionService) {
        // TODO Auto-generated method stub
    }

    public static void setItemRegistry(ItemRegistry itemRegistry) {
        ForecastAction.itemRegistry = itemRegistry;
    }
}
