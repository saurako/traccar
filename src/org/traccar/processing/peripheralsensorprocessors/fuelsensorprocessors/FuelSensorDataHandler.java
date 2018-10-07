package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.google.common.collect.BoundType;
import com.google.common.collect.SortedMultiset;
import com.google.common.collect.TreeMultiset;
import org.apache.commons.lang.StringUtils;
import org.traccar.BaseDataHandler;
import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity.FuelActivityType;
import org.traccar.transforms.model.FuelSensorCalibration;
import org.traccar.transforms.model.SensorPointsMap;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.traccar.Context.getDataManager;

public class FuelSensorDataHandler extends BaseDataHandler {

    private static final int INVALID_FUEL_FREQUENCY = 0xFFF;
    private static final String FUEL_ANALOG = "FUEL_ANALOG";
    private static final String SENSOR_ID = "sensorId";
    private static final String SENSOR_DATA = "sensorData";
    private static final String ADC_1 = "adc1";
    private static final String FREQUENCY_PREFIX = "F=";
    private static final String FUEL_PART_PREFIX = "N=";

    private static final int SECONDS_IN_ONE_HOUR = 3600;

    private int minValuesForMovingAvg;
    private int minValuesForOutlierDetection;
    private int maxInMemoryPreviousPositionsListSize;
    private int hoursOfDataToLoad;
    private int minHoursOfDataInMemory;
    private int maxValuesForAlerts;
    private int storedEventLookAroundSeconds;
    private int currentEventLookBackSeconds;
    private double fuelLevelChangeThresholdLitresDigital;
    private double fuelLevelChangeThresholdLitresAnalog;
    private double fuelErrorThreshold;

    private final Map<Long, Map<Integer, TreeMultiset<Position>>> previousPositions =
            new ConcurrentHashMap<>();

    private final Map<String, FuelEventMetadata> deviceFuelEventMetadata =
            new ConcurrentHashMap<>();

    private final Map<Long, Position> deviceLastKnownOdometerPositionLookup =
            new ConcurrentHashMap<>();

    private boolean loadingOldDataFromDB = false;

    public FuelSensorDataHandler() {
        initializeConfig();
        loadOldPositions();
    }

    public FuelSensorDataHandler(boolean loader) {
        // Do nothing constructor for tests.
    }

    private void initializeConfig() {
        int messageFrequencyInSeconds =
                Context.getConfig().getInteger("processing.peripheralSensorData.messageFrequency");

        hoursOfDataToLoad = Context.getConfig()
                                   .getInteger("processing.peripheralSensorData.hoursOfDataToLoad");

        minValuesForMovingAvg = Context.getConfig()
                                       .getInteger("processing.peripheralSensorData.minValuesForMovingAverage");

        minValuesForOutlierDetection =
                Context.getConfig()
                       .getInteger("processing.peripheralSensorData.minValuesForOutlierDetection");

        minHoursOfDataInMemory = Context.getConfig()
                                        .getInteger("processing.peripheralSensorData.minHoursOfDataInMemory");

        // If hoursOfDataToLoad = 0, then keep at least minHoursOfDataInMemory hours of data in memory
        maxInMemoryPreviousPositionsListSize =
                (SECONDS_IN_ONE_HOUR * (hoursOfDataToLoad > 0
                        ? hoursOfDataToLoad : minHoursOfDataInMemory)) / messageFrequencyInSeconds;

        maxValuesForAlerts = Context.getConfig()
                                    .getInteger("processing.peripheralSensorData.maxValuesForAlerts");
        storedEventLookAroundSeconds =
                Context.getConfig()
                       .getInteger("processing.peripheralSensorData.storedEventLookAroundSeconds");

        currentEventLookBackSeconds =
                Context.getConfig()
                       .getInteger("processing.peripheralSensorData.currentEventLookBackSeconds");

        fuelLevelChangeThresholdLitresDigital =
                Context.getConfig()
                       .getDouble("processing.peripheralSensorData.fuelLevelChangeThresholdLitersDigital");

        fuelLevelChangeThresholdLitresAnalog =
                Context.getConfig()
                       .getDouble("processing.peripheralSensorData.fuelLevelChangeThresholdLitersAnalog");

        fuelErrorThreshold = Context.getConfig().getDouble("processing.peripheralSensorData.fuelErrorThreshold");
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            Optional<List<PeripheralSensor>> peripheralSensorsOnDevice =
                    getLinkedDevices(position.getDeviceId());

            if (!peripheralSensorsOnDevice.isPresent()) {
                return position;
            }

            updateLatestKnownPosition(position);

            Optional<Integer> sensorIdOnPosition =
                    getSensorId(position, peripheralSensorsOnDevice.get());

            if (!sensorIdOnPosition.isPresent()) {
                // This is possibly a position either from a device that does not have any sensors on it,
                // OR a non-sensor payload
                updateWithLastAvailable(position, Position.KEY_FUEL_LEVEL);
                return position;
            }

            if (position.getAttributes().containsKey(SENSOR_DATA)) {
                // Digital fuel sensor data
                handleDigitalFuelSensorData(position, sensorIdOnPosition.get(), fuelLevelChangeThresholdLitresDigital);
            }

            if (position.getAttributes().containsKey(ADC_1)) {
                handleAnalogFuelSensorData(position, sensorIdOnPosition.get(), fuelLevelChangeThresholdLitresAnalog);
            }
        } catch (Exception e) {
            Log.debug(String.format("Exception in processing fuel info: %s", e.getMessage()));
            e.printStackTrace();
        } finally {
            return position;
        }
    }

    private void updateLatestKnownPosition(Position position) {

        if (!position.getAttributes().containsKey(Position.KEY_ODOMETER)
                || !position.getAttributes().containsKey(Position.KEY_TOTAL_DISTANCE)) {

            return;
        }

        Optional<Position> lastKnown = deviceLastKnownOdometerPositionLookup.containsKey(position.getDeviceId())?
                Optional.of(deviceLastKnownOdometerPositionLookup.get(position.getDeviceId())) : Optional.empty();

        if (!lastKnown.isPresent()) {
            deviceLastKnownOdometerPositionLookup.put(position.getDeviceId(), position);
            return;
        }

        Position lastKnownPosition = lastKnown.get();
        Map<String, Object> lastKnownAttributes = lastKnownPosition.getAttributes();
        Map<String, Object> currentAttributes = position.getAttributes();

        if (lastKnownPosition.getDeviceTime().getTime() > position.getDeviceTime().getTime()
            || ((int) lastKnownAttributes.get(Position.KEY_ODOMETER) >
                    (int) currentAttributes.get(Position.KEY_ODOMETER))
            || ((double) lastKnownAttributes.get(Position.KEY_TOTAL_DISTANCE) >
                    (double) lastKnownAttributes.get(Position.KEY_TOTAL_DISTANCE))) {

            return;
        }

        deviceLastKnownOdometerPositionLookup.put(position.getDeviceId(), position);
    }

    private void updateWithLastAvailable(final Position position, final String attributeToUpdate) {
        long deviceId = position.getDeviceId();
        if (!previousPositions.containsKey(deviceId)) {
            Log.debug("deviceId not found in previousPositions" + deviceId);
            return;
        }

        Map<Integer, TreeMultiset<Position>> sensorReadingsFromDevice = previousPositions.get(deviceId);

        if (sensorReadingsFromDevice.size() < 1) {
            Log.debug("No readings for sensors found on deviceId: " + deviceId);
            return;
        }

        Optional<Integer> sensorId = sensorReadingsFromDevice.keySet().stream().findFirst();

        if (!sensorId.isPresent() || sensorReadingsFromDevice.get(sensorId.get()).size() < 1) {
            Log.debug("No relevant sensorId found on deviceId: " + deviceId + ": "
                 + "sensorId present: " + sensorId.isPresent()
                 + "keySet: " + sensorReadingsFromDevice.keySet()
                 + " readings: " + (sensorId.isPresent() ? sensorReadingsFromDevice.get(sensorId.get()).size() : 0));
            return;
        }

        // This should ideally average the readings from all sensors, but for now we'll just pick the first sensor and
        // use the last available level.

        Optional<Position> possibleLastKnownPosition = getLastKnownPositionForDevice(position);
        if (!possibleLastKnownPosition.isPresent()) {
            Log.debug("Last known position not found for deviceId: " + position.getDeviceId());
            return;
        }

        Position lastKnownPosition = possibleLastKnownPosition.get();
        if (!lastKnownPosition.getAttributes().containsKey(attributeToUpdate)) {
            Log.debug(String.format("Last known position for deviceId %d doesn't have property %s set.", position.getDeviceId(), attributeToUpdate));
            return;
        }

        switch(attributeToUpdate) {
            case Position.KEY_FUEL_LEVEL:
                position.set(attributeToUpdate, (double) lastKnownPosition.getAttributes().get(attributeToUpdate));
                break;
        }
    }

    private Optional<Position> getLastKnownPositionForDevice(Position position) {
        long deviceId = position.getDeviceId();
        if (!previousPositions.containsKey(deviceId)) {
            Log.debug("deviceId not found in previousPositions" + deviceId);
            Optional.empty();
        }

        Map<Integer, TreeMultiset<Position>> sensorReadingsFromDevice = previousPositions.get(deviceId);

        if (sensorReadingsFromDevice.size() < 1) {
            Log.debug("No readings for sensors found on deviceId: " + deviceId);
            Optional.empty();
        }

        Optional<Integer> sensorId = sensorReadingsFromDevice.keySet().stream().findFirst();

        if (!sensorId.isPresent() || sensorReadingsFromDevice.get(sensorId.get()).size() < 1) {
            Log.debug("No relevant sensorId found on deviceId: " + deviceId + ": "
                    + "sensorId present: " + sensorId.isPresent()
                    + "keySet: " + sensorReadingsFromDevice.keySet()
                    + " readings: " + (sensorId.isPresent() ? sensorReadingsFromDevice.get(sensorId.get()).size() : 0));
            Optional.empty();
        }

        final Position lastKnownPosition = sensorReadingsFromDevice.get(sensorId.get())
                                                             .lastEntry()
                                                             .getElement();

        return Optional.of(lastKnownPosition);
    }

    private void loadOldPositions() {
        this.loadingOldDataFromDB = true;
        Collection<Device> devices = Context.getDeviceManager().getAllDevices();

        Date hoursAgo = getAdjustedDate(new Date(), Calendar.HOUR_OF_DAY, -this.hoursOfDataToLoad);

        for (Device device : devices) {
            Optional<List<PeripheralSensor>> linkedDevices =
                    getLinkedDevices(device.getId());

            if (!linkedDevices.isPresent()) {
                continue;
            }

            try {
                Collection<Position> devicePositionsInLastDay =
                        getDataManager().getPositions(device.getId(), hoursAgo, new Date());

                for (Position position : devicePositionsInLastDay) {
                    handlePosition(position);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.loadingOldDataFromDB = false;
    }

    private Optional<List<PeripheralSensor>> getLinkedDevices(long deviceId) {
        return Context.getPeripheralSensorManager()
                      .getLinkedPeripheralSensors(deviceId);
    }

    private Optional<Integer> getSensorId(Position position, List<PeripheralSensor> peripheralSensorsOnDevice) {

        if (position.getAttributes().containsKey(SENSOR_ID)) {
            final int positionSensorId = (int) position.getAttributes().get(SENSOR_ID);
            Optional<PeripheralSensor> digitalSensor =
                    peripheralSensorsOnDevice.stream()
                                             .filter(p -> p.getPeripheralSensorId() == positionSensorId).findFirst();

            return digitalSensor.map(sensor -> (int) sensor.getPeripheralSensorId());
        }

        if (position.getAttributes().containsKey(ADC_1)) {
            Optional<PeripheralSensor> analogSensor =
                    peripheralSensorsOnDevice.stream()
                                             .filter(p -> p.getTypeName().equals(FUEL_ANALOG)).findFirst();

            return analogSensor.map(sensor -> (int) sensor.getPeripheralSensorId());
        }

        return Optional.empty();
    }

    private void handleDigitalFuelSensorData(Position position,
                                             int sensorId,
                                             double fuelLevelChangeThreshold) {

        String sensorDataString = (String) position.getAttributes().get(SENSOR_DATA);
        if (StringUtils.isBlank(sensorDataString)) {
            return;
        }

        // Since digital fuel level payloads don't have the odometer reading, let's update it with the one on
        // the last known position. This will help downstream processing where this is needed e.g. data loss check.
        long deviceId = position.getDeviceId();
        if (deviceLastKnownOdometerPositionLookup.containsKey(deviceId)) {
            Position lastPosition = deviceLastKnownOdometerPositionLookup.get(deviceId);
            position.set(Position.KEY_ODOMETER, (int) lastPosition.getAttributes().get(Position.KEY_ODOMETER));
            //position.set(Position.KEY_TOTAL_DISTANCE, (double) lastPosition.getAttributes()
              //                                                           .get(Position.KEY_TOTAL_DISTANCE));
        }

        Optional<Long> fuelLevelPoints =
                getFuelLevelPointsFromDigitalSensorData(sensorDataString);

        if (!fuelLevelPoints.isPresent()) {
            return;
        }

        handleSensorData(position,
                sensorId,
                fuelLevelPoints.get(),
                fuelLevelChangeThreshold);
    }

    private void handleAnalogFuelSensorData(Position position,
                                            int sensorId, double fuelLevelChangeThreshold) {

        if ((position.getAttributes().containsKey(Position.PREFIX_ADC + 1) &&
                (int) position.getAttributes().get(Position.PREFIX_ADC + 1)  <= 0) ||
                (position.getAttributes().containsKey(Position.KEY_POWER) &&
                        (int) position.getAttributes().get(Position.KEY_POWER)  <= 0)) {

            Log.debug("Device power too low or missing, updating with last known fuel level for deviceId"
                    + position.getDeviceId());
            updateWithLastAvailable(position, Position.KEY_FUEL_LEVEL);
            return;
        }

        Long fuelLevel = new Long((Integer) position.getAttributes().get(ADC_1));

        handleSensorData(position,
                sensorId,
                fuelLevel,
                fuelLevelChangeThreshold);
    }

    private void handleSensorData(Position position,
                                  Integer sensorId,
                                  Long fuelLevelPoints, double fuelLevelChangeThreshold) {

        long deviceId = position.getDeviceId();

        if (!previousPositions.containsKey(deviceId) || !previousPositions.get(deviceId).containsKey(sensorId)) {
            TreeMultiset<Position> positions =
                    TreeMultiset.create(Comparator.comparing(p -> p.getDeviceTime().getTime()));
            Map<Integer, TreeMultiset<Position>> sensorPositions = new ConcurrentHashMap<>();
            sensorPositions.put(sensorId, positions);
            previousPositions.put(deviceId, sensorPositions);
        }

        TreeMultiset<Position> positionsForDeviceSensor = previousPositions.get(deviceId).get(sensorId);

        if (loadingOldDataFromDB || position.getAttributes().containsKey(Position.KEY_CALIBRATED_FUEL_LEVEL)) {
            // This is a position from the DB, add to the list and move on.
            // If we don't skip further processing, it might trigger FCM notification unnecessarily.
            positionsForDeviceSensor.add(position);
            removeFirstPositionIfNecessary(positionsForDeviceSensor, deviceId);
            return;
        }

        Optional<Double> maybeCalibratedFuelLevel =
                getCalibratedFuelLevel(deviceId, sensorId, fuelLevelPoints);

        if (!maybeCalibratedFuelLevel.isPresent()) {
            // We don't have calibration data for sensor. We will try to load it up again, to handle a device
            // that was added newly. But we will not process this position because we have to check again if the calib
            // is there or not and that can go into a loop. We'll check back the next time we get an update position
            // from this device.
            Log.debug("Calibration data not found for sensor, refreshing calib list from db" + sensorId);
            Context.getPeripheralSensorManager().refreshPeripheralSensorsMap();
            return;
        }

        double calibratedFuelLevel = maybeCalibratedFuelLevel.get();
        position.set(Position.KEY_CALIBRATED_FUEL_LEVEL, calibratedFuelLevel);

        List<Position> relevantPositionsListForAverages =
                getRelevantPositionsSubList(positionsForDeviceSensor,
                                            position,
                                            minValuesForMovingAvg - 1);

        relevantPositionsListForAverages.add(position);
        double currentFuelLevelAverage = getAverageValue(relevantPositionsListForAverages);

        // KEY_FUEL_LEVEL will hold the smoothed data, which is average of raw values in the relevant list.
        // Until the number of positions in the list comes up to the expected number of positions for calculating
        // averages and / or outliers, this will calculate the average of the existing list and set that on the
        // current position, so it's available for later calculations. This will also make sure that we are able to send
        // this info to any client that's listening for these updates.

        position.set(Position.KEY_FUEL_LEVEL, currentFuelLevelAverage);
        positionsForDeviceSensor.add(position);

        // Detect and remove outliers
        List<Position> relevantPositionsListForOutliers =
                getRelevantPositionsSubList(positionsForDeviceSensor, position, minValuesForOutlierDetection);

        // Detect data loss if size of relevantPositionsListForOutliers is = 1
        if (relevantPositionsListForOutliers.size() == 1) {
            Position last = getLastPosition(position.getDeviceId());
            if (last == null) {
                return;
            }
            double startTotalGPSDistanceInMeters = last.getDouble(Position.KEY_TOTAL_DISTANCE);
            double endTotalGPSDistanceInMeters = position.getDouble(Position.KEY_TOTAL_DISTANCE);

            int startOdometerInMeters = 0;
            int endOdometerInMeters = 0;
            if (last.getAttributes().containsKey(Position.KEY_ODOMETER)
                    && position.getAttributes().containsKey(Position.KEY_ODOMETER)) {
                startOdometerInMeters = last.getInteger(Position.KEY_ODOMETER);
                endOdometerInMeters = position.getInteger(Position.KEY_ODOMETER);
            }

            double calculatedFuelChangeVolume = position.getInteger(Position.KEY_FUEL_LEVEL)
                    - last.getInteger(Position.KEY_FUEL_LEVEL);

            double differenceTotalDistanceInMeters = endTotalGPSDistanceInMeters - startTotalGPSDistanceInMeters;
            double differenceOdometerInMeters = endOdometerInMeters - startOdometerInMeters;
            double maximumDistanceTravelled = Math.max(differenceTotalDistanceInMeters, differenceOdometerInMeters) / 1000;
            double minimumAverageMileage = 1.5; // This has to be a self learning value
            double maximumAverageMileage = 4.0; // This has to be a self learning value
            double currentAverageMileage = 2.5; // This has to be a self learning value
            double expectedMaxFuelConsumed = maximumDistanceTravelled / minimumAverageMileage;
            double expectedMinFuelConsumed = maximumDistanceTravelled / maximumAverageMileage;
            double expectedCurrentFuelConsumed = maximumDistanceTravelled / currentAverageMileage;
            Log.debug("Expfuel-" + expectedCurrentFuelConsumed + " MaxFuel-" + expectedMaxFuelConsumed
                    + " Minfuel-" + expectedMinFuelConsumed + " maxdist-" + maximumDistanceTravelled
                    + " fuelchange-" + calculatedFuelChangeVolume);
            //here we process if calculated fuel volume is bigger than certain number,
            // in order to omit notifications due to fluctuation in reading from sensor in a still vehicle
            //currently hardcoded to 2. Should be like 1% of the tank volume may be as that is the allowed error
            if (Math.abs(calculatedFuelChangeVolume) > 2) {
                if (calculatedFuelChangeVolume < 0) {
                    if (Math.abs(calculatedFuelChangeVolume) <= expectedMaxFuelConsumed
                            && Math.abs(calculatedFuelChangeVolume) >= expectedMinFuelConsumed) {
                        Log.debug(String.format("Data Loss: Distance covered %f, Exp fuel consumed: %f, actual fuel consumed: %f",
                                maximumDistanceTravelled, expectedCurrentFuelConsumed, calculatedFuelChangeVolume));
                    } else if (Math.abs(calculatedFuelChangeVolume) > expectedMaxFuelConsumed) {
                        double possibleFuelDrain = Math.abs(calculatedFuelChangeVolume) - expectedCurrentFuelConsumed;
                        //send notification for possibleFuelDrain;
                    } else {
                        double possibleFuelFill = expectedCurrentFuelConsumed - Math.abs(calculatedFuelChangeVolume);
                        //send notification for possibleFuelFill;
                    }
                } else {
                    double expectedFuelFill = calculatedFuelChangeVolume + expectedCurrentFuelConsumed;
                    //send notification for expectedFuelFill;
                }
            }
        }

        if (relevantPositionsListForOutliers.size() < minValuesForOutlierDetection) {
            Log.debug("List too small for outlier detection");
            return;
        }

        int indexOfPositionEvaluation = (minValuesForOutlierDetection - 1) / 2;
        boolean outlierPresent = FuelSensorDataHandlerHelper.isOutlierPresentInSublist(
                relevantPositionsListForOutliers,
                indexOfPositionEvaluation);

        if (outlierPresent) {
            // Remove the outlier
            Position outlierPosition = relevantPositionsListForOutliers.get(indexOfPositionEvaluation);
            outlierPosition.set(Position.KEY_FUEL_IS_OUTLIER, true);

            // Note: Need to do this in a better way since this is a direct write to the db and can slow things down.
            // We could use an external queue and update these positions from there, without affecting processing here.
            // Also, we do not want to lose any data coming in, so we'll only mark the position as an outlier rather
            // than deleting it.
            updatePosition(outlierPosition);
            positionsForDeviceSensor.remove(outlierPosition);
            return;
        }

        Position positionUnderEvaluation = relevantPositionsListForOutliers.get(indexOfPositionEvaluation);

        // Re-calculate and reset averages if there were no outliers.
        relevantPositionsListForAverages =
                getRelevantPositionsSubList(positionsForDeviceSensor,
                                            positionUnderEvaluation,
                                            minValuesForMovingAvg);

        currentFuelLevelAverage = getAverageValue(relevantPositionsListForAverages);
        positionUnderEvaluation.set(Position.KEY_FUEL_LEVEL, currentFuelLevelAverage);

        // Update the position in the db so the recalculated average is reflected there.
        updatePosition(positionUnderEvaluation);

        //-- End Outliers

        List<Position> relevantPositionsListForAlerts =
                getRelevantPositionsSubList(positionsForDeviceSensor,
                                            position,
                                            maxValuesForAlerts);

        if (!this.loadingOldDataFromDB && relevantPositionsListForAlerts.size() >= maxValuesForAlerts) {
            // We'll use the smoothed values to check for activity.
            FuelActivity fuelActivity =
                    checkForActivity(relevantPositionsListForAlerts,
                                     deviceFuelEventMetadata,
                                     sensorId,
                                     fuelLevelChangeThreshold,
                                     fuelErrorThreshold);

            if (fuelActivity.getActivityType() != FuelActivity.FuelActivityType.NONE) {
                Log.debug("[FUEL_ACTIVITY]  DETECTED: " + fuelActivity.getActivityType()
                         + " starting at: " + fuelActivity.getActivityStartTime()
                         + " ending at: " + fuelActivity.getActivityEndTime()
                         + " volume: " + fuelActivity.getChangeVolume()
                         + " start lat, long " + fuelActivity.getActivitystartPosition().getLatitude()
                         + ", " + fuelActivity.getActivitystartPosition().getLongitude()
                         + " end lat, long " + fuelActivity.getActivityEndPosition().getLatitude()
                         + ", " + fuelActivity.getActivityEndPosition().getLongitude());

                // Add event to events table
                String eventType =
                        fuelActivity.getActivityType() == FuelActivityType.FUEL_FILL
                                ? Event.TYPE_FUEL_FILL
                                : Event.TYPE_FUEL_DRAIN;

                Event event = new Event(eventType, position.getDeviceId(),
                                        fuelActivity.getActivitystartPosition().getId());
                event.set("startTime", fuelActivity.getActivityStartTime().getTime());
                event.set("endTime", fuelActivity.getActivityEndTime().getTime());
                event.set("volume", fuelActivity.getChangeVolume());
                event.set("endPositionId", fuelActivity.getActivityEndPosition().getId());

                try {
                    getDataManager().addObject(event);
                } catch (SQLException error) {
                    Log.warning(error);
                }

                Context.getFcmPushNotificationManager().updateFuelActivity(fuelActivity);
            }
        }

        removeFirstPositionIfNecessary(positionsForDeviceSensor, deviceId);
    }

    private static void updatePosition(final Position outlierPosition) {
        try {
            Context.getDataManager().updateObject(outlierPosition);
        } catch (SQLException e) {
            Log.debug("Exception while updating outlier position with id: " + outlierPosition.getId());
        }
    }

    private void removeFirstPositionIfNecessary(TreeMultiset<Position> positionsForDeviceSensor, long deviceId) {
        if (positionsForDeviceSensor.size() > maxInMemoryPreviousPositionsListSize) {
            Position toRemove = positionsForDeviceSensor.firstEntry().getElement();
            positionsForDeviceSensor.remove(toRemove);
            Log.debug("Size of positionsForDeviceSensor with deviceId = " + deviceId
                      + " after removing position: " + positionsForDeviceSensor.size());
        }
    }

    private Date getAdjustedDate(Date fromDate, int type, int amount) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(fromDate);
        cal.add(type, amount);
        return cal.getTime();
    }

    private Double getAverageValue(List<Position> fuelLevelReadings) {

        // Omit values that are 0s, to avoid skewing the average. This is mostly useful in handling 0s from the
        // analog sensor, which are noise.
        double total = 0.0;
        double size = 0.0;

        for (Position position : fuelLevelReadings) {
            double level = (Double) position.getAttributes().get(Position.KEY_CALIBRATED_FUEL_LEVEL);
            if (level > 0.0 && !Double.isNaN(level)) {
                total += level;
                size += 1.0;
            }
        }

        if (size == 0.0) {
            return 0.0;
        }

        double avg = total / size;

        Log.debug("[FUEL_ACTIVITY_AVERAGES] deviceId: " + fuelLevelReadings.get(0).getDeviceId()
                  + " average: " + avg
                  + " averages list size: " + fuelLevelReadings.size());

        return avg;
    }


    private List<Position> getRelevantPositionsSubList(TreeMultiset<Position> positionsForSensor,
                                                       Position position,
                                                       int minListSize) {

        if (positionsForSensor.size() <= minListSize) {
            return positionsForSensor.stream()
                                     .collect(Collectors.toList());
        }

        Position fromPosition = new Position();
        fromPosition.setDeviceTime(getAdjustedDate(position.getDeviceTime(),
                                                   Calendar.SECOND,
                                                   -currentEventLookBackSeconds));

        SortedMultiset<Position> positionsSubset =
                positionsForSensor.subMultiset(fromPosition, BoundType.OPEN, position, BoundType.CLOSED);

        if (positionsSubset.size() <= minListSize) {
            Log.debug("[RELEVANT_SUBLIST] sublist is lesser than "
                      + minListSize + " returning " + positionsSubset.size());
            return positionsSubset.stream()
                                  .collect(Collectors.toList());
        }

        int listMaxIndex = positionsSubset.size();

        List<Position> sublistToReturn =  positionsSubset.stream()
                                                         .collect(Collectors.toList())
                                                         .subList(listMaxIndex - minListSize, listMaxIndex);

        Log.debug("[RELEVANT_SUBLIST] sublist size: " + sublistToReturn.size());

        return sublistToReturn;
    }

    private Optional<Double> getCalibratedFuelLevel(Long deviceId, Integer sensorId, Long sensorFuelLevelPoints) {

        Optional<FuelSensorCalibration> fuelSensorCalibration =
                Context.getPeripheralSensorManager().
                        getDeviceSensorCalibrationData(deviceId, sensorId);

        if (!fuelSensorCalibration.isPresent()) {
            return Optional.empty();
        }

        // Make a B-tree map of the points to fuel level map
        TreeMap<Long, SensorPointsMap> sensorPointsToVolumeMap =
                new TreeMap<>(fuelSensorCalibration.get().getSensorPointsMap());

        SensorPointsMap previousFuelLevelInfo = sensorPointsToVolumeMap.floorEntry(sensorFuelLevelPoints).getValue();
        SensorPointsMap nextFuelLevelInfo = sensorPointsToVolumeMap.ceilingEntry(sensorFuelLevelPoints).getValue();

        if (nextFuelLevelInfo == null) {
            Log.debug("Null nextFuelLevelInfo - deviceID: " + deviceId
                     + " sensorId: " + sensorId);
            return Optional.empty();
        }

        try {
            double currentAveragePointsPerLitre = nextFuelLevelInfo.getPointsPerLitre();
            long previousPoint = sensorPointsToVolumeMap.floorKey(sensorFuelLevelPoints);
            long previousFuelLevel = previousFuelLevelInfo.getFuelLevel();

            double fuelLevel = currentAveragePointsPerLitre > 0.0
                    ? ((sensorFuelLevelPoints - previousPoint) / currentAveragePointsPerLitre) + previousFuelLevel
                    : 0.0;

            return Optional.of(fuelLevel);
        } catch (Exception ex) {
            Log.debug("Null nextFuelLevelInfo - deviceID: " + deviceId
                     + " sensorId: " + sensorId
                    + " reported sensorFuelLevelPoints" + sensorFuelLevelPoints);

            Log.debug("fuel calibration: ");
            fuelSensorCalibration.ifPresent(fuelSensorCalibration1 -> fuelSensorCalibration1
                    .getSensorPointsMap()
                    .entrySet().forEach(e -> Log.debug(e.getKey() + " -> " + e.getValue().getFuelLevel()
                                                     + " , " + e.getValue().getPointsPerLitre())));
        }
        return Optional.empty();
    }

    public FuelActivity checkForActivity(List<Position> readingsForDevice,
                                                Map<String, FuelEventMetadata> deviceFuelEventMetadata,
                                                long sensorId,
                                                double fuelLevelChangeThreshold,
                                                double fuelErrorThreshold) {

        FuelActivity fuelActivity = new FuelActivity();

        int midPoint = (readingsForDevice.size() - 1) / 2;
        double leftSum = 0, rightSum = 0;

        for (int i = 0; i <= midPoint; i++) {
            leftSum += (double) readingsForDevice.get(i).getAttributes().get(Position.KEY_FUEL_LEVEL);
            rightSum += (double) readingsForDevice.get(i + midPoint).getAttributes().get(Position.KEY_FUEL_LEVEL);
        }

        double leftMean = leftSum / (midPoint + 1);
        double rightMean = rightSum / (midPoint + 1);
        double diffInMeans = Math.abs(leftMean - rightMean);

        long deviceId = readingsForDevice.get(0).getDeviceId();
        String lookupKey = deviceId + "_" + sensorId;
        Log.debug("[FUEL_ACTIVITY] deviceId: " + deviceId + "diffInMeans: " + diffInMeans
                  + " fuelLevelChangeThreshold: " + fuelLevelChangeThreshold
                  + " diffInMeans > fuelLevelChangeThreshold: " + (diffInMeans > fuelLevelChangeThreshold));

        if (diffInMeans > fuelLevelChangeThreshold) {

            if (!deviceFuelEventMetadata.containsKey(lookupKey)) {

                Position midPointPosition = readingsForDevice.get(midPoint);

                deviceFuelEventMetadata.put(lookupKey, new FuelEventMetadata());

                FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
                fuelEventMetadata.setStartLevel((double) midPointPosition.getAttributes()
                                                                          .get(Position.KEY_FUEL_LEVEL));

                fuelEventMetadata.setErrorCheckStart((double) readingsForDevice.get(0)
                                                                               .getAttributes()
                                                                               .get(Position.KEY_FUEL_LEVEL));

                fuelEventMetadata.setStartTime(midPointPosition.getDeviceTime());
                fuelEventMetadata.setActivityStartPosition(midPointPosition);

                Log.debug("[FUEL_ACTIVITY_START] Activity start detected: deviceId" + deviceId + " at: "
                         + midPointPosition.getDeviceTime());

                StringBuilder rawFuelValuesInReadings = new StringBuilder();
                StringBuilder timestamps = new StringBuilder();
                readingsForDevice.forEach(p -> {
                    rawFuelValuesInReadings.append((double) p.getAttributes()
                                                             .get(Position.KEY_CALIBRATED_FUEL_LEVEL) + ", ");
                    timestamps.append(p.getDeviceTime());
                });
                Log.debug("[FUEL_ACTIVITY_START] rawFuelValues that crossed threshold for deviceId: " + deviceId
                          + " - " + rawFuelValuesInReadings);
                Log.debug("[FUEL_ACTIVITY_START] corresponding timestamps: " + timestamps);
                Log.debug("[FUEL_ACTIVITY_START] Midpoint: "
                          + midPointPosition.getAttributes()
                                            .get(Position.KEY_CALIBRATED_FUEL_LEVEL));
                Log.debug("[FUEL_ACTIVITY_START] metadata: " + fuelEventMetadata);

            }
        }

        if (diffInMeans < fuelLevelChangeThreshold && deviceFuelEventMetadata.containsKey(lookupKey)) {

            Position midPointPosition = readingsForDevice.get(midPoint);

            FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
            fuelEventMetadata.setEndLevel((double) midPointPosition.getAttributes()
                                                                    .get(Position.KEY_FUEL_LEVEL));
            fuelEventMetadata.setErrorCheckEnd((double) readingsForDevice.get(readingsForDevice.size() - 1)
                                                                         .getAttributes()
                                                                         .get(Position.KEY_FUEL_LEVEL));
            fuelEventMetadata.setEndTime(midPointPosition.getDeviceTime());
            fuelEventMetadata.setActivityEndPosition(midPointPosition);

            double fuelChangeVolume = fuelEventMetadata.getEndLevel() - fuelEventMetadata.getStartLevel();
            double errorCheckFuelChange = fuelEventMetadata.getErrorCheckEnd() - fuelEventMetadata.getErrorCheckStart();
            double errorCheck = fuelChangeVolume * fuelErrorThreshold;

            Log.debug("[FUEL_ACTIVITY_END] Activity end detected: deviceId" + deviceId + " at: "
                      + midPointPosition.getDeviceTime());

            StringBuilder rawFuelValuesInReadings = new StringBuilder();
            StringBuilder timestamps = new StringBuilder();
            readingsForDevice.forEach(p -> {
                rawFuelValuesInReadings.append((double) p.getAttributes()
                                                         .get(Position.KEY_CALIBRATED_FUEL_LEVEL) + ", ");
                timestamps.append(p.getDeviceTime());
            });
            Log.debug("[FUEL_ACTIVITY_END] rawFuelValues that crossed threshold for deviceId: " + deviceId
                      + " - " + rawFuelValuesInReadings);
            Log.debug("[FUEL_ACTIVITY_END] corresponding timestamps: " + timestamps);
            Log.debug("[FUEL_ACTIVITY_END] Midpoint: " + midPointPosition.getAttributes()
                                                                         .get(Position.KEY_CALIBRATED_FUEL_LEVEL));
            Log.debug("[FUEL_ACTIVITY_END] metadata: " + fuelEventMetadata);
            Log.debug("[FUEL_ACTIVITY_END] fuelErrorThreshold: " + fuelErrorThreshold);
            Log.debug("[FUEL_ACTIVITY_END] fuelChangeVolume: " + fuelChangeVolume);
            Log.debug("[FUEL_ACTIVITY_END] errorCheckFuelChange: " + errorCheckFuelChange);
            Log.debug("[FUEL_ACTIVITY_END] errorCheck: " + errorCheck);

            boolean isDataLoss = FuelSensorDataHandlerHelper.isDataLoss(fuelEventMetadata, fuelChangeVolume);

            if (!isDataLoss && fuelChangeVolume < 0.0) {
                fuelActivity.setActivityType(FuelActivityType.FUEL_DRAIN);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivitystartPosition(fuelEventMetadata.getActivityStartPosition());
                fuelActivity.setActivityEndPosition(fuelEventMetadata.getActivityEndPosition());
                deviceFuelEventMetadata.remove(lookupKey);
            } else if (fuelChangeVolume > 0.0) {
                fuelActivity.setActivityType(FuelActivityType.FUEL_FILL);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivitystartPosition(fuelEventMetadata.getActivityStartPosition());
                fuelActivity.setActivityEndPosition(fuelEventMetadata.getActivityEndPosition());
                deviceFuelEventMetadata.remove(lookupKey);
            } else {
                // The start may have been detected as a false positive. In any case, remove after we determine the kind
                // of activity.
                Log.debug("[FUEL_ACTIVITY] Removing event metadata from list to avoid false positives: "
                        + lookupKey);
                deviceFuelEventMetadata.remove(lookupKey);
            }
        }

        return fuelActivity;
    }

    private Optional<Long> getFuelLevelPointsFromDigitalSensorData(String sensorDataString) {
        if (StringUtils.isBlank(sensorDataString)) {
            return Optional.empty();
        }

        String[] sensorDataParts = sensorDataString.split(" "); // Split on space to get the 3 parts
        String frequencyString = sensorDataParts[0];
        String temperatureString = sensorDataParts[1];
        String fuelLevelPointsString = sensorDataParts[2];

        if (StringUtils.isBlank(frequencyString)
            || StringUtils.isBlank(temperatureString)
            || StringUtils.isBlank(fuelLevelPointsString)) {

            return Optional.empty();
        }

        String[] frequencyParts = frequencyString.split(FREQUENCY_PREFIX);
        if (frequencyParts.length < 2) {
            return Optional.empty();
        }

        // If frequency is > xFFF (4096), it is invalid per the spec; so ignore it.
        Long frequency = Long.parseLong(frequencyParts[1], 16);
        if (frequency > INVALID_FUEL_FREQUENCY) {
            return Optional.empty();
        }

        String[] fuelParts = fuelLevelPointsString.split(FUEL_PART_PREFIX);
        if (fuelParts.length < 2) {
            return Optional.empty();
        }

        Long fuelSensorPoints = Long.parseLong(fuelParts[1].split("\\.")[0], 16);
        return Optional.of(fuelSensorPoints);
    }
    private Position getLastPosition(long deviceId) {
        if (Context.getIdentityManager() != null) {
            return Context.getIdentityManager().getLastPosition(deviceId);
        }
        Log.debug("returning null");
        return null;
    }
}
