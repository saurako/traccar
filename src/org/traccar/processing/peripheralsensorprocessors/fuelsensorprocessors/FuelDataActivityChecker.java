package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Position;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FuelDataActivityChecker {

    private static final Double fuelLevelChangeThreshold;

    static {
        fuelLevelChangeThreshold =
                Context.getConfig()
                       .getDouble("processing.peripheralSensorData.fuelLevelChangeThresholdLiters");
    }

    public static FuelActivity checkForActivity(List<Position> readingsForDevice,
                                                Map<String, FuelEventMetadata> deviceFuelEventMetadata,
                                                Long sensorId) {

        FuelActivity fuelActivity = new FuelActivity();

        int midPoint = (readingsForDevice.size() - 1) / 2;
        double leftSum = 0, rightSum = 0;

        for (int i = 0; i <= midPoint; i++) {
            leftSum += (double) readingsForDevice.get(i).getAttributes().get(Position.KEY_CALIBRATED_FUEL_LEVEL);
            rightSum += (double) readingsForDevice.get(i + midPoint).getAttributes().get(Position.KEY_CALIBRATED_FUEL_LEVEL);
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
                Position midPointPosition = readingsForDevice.get(readingsForDevice.size()-1);

                double thresholdForStart = rightMean > leftMean? 0.05 : -0.05;

                for (int j = midPoint; j < readingsForDevice.size(); j++) {
                    double diffInRaw = readingsForDevice.get(j).getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL) -
                        readingsForDevice.get(j-1).getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);;

                    if (rightMean > leftMean && diffInRaw >= thresholdForStart) {
                        midPointPosition = readingsForDevice.get(j-1);
                        break;
                    } else if (rightMean < leftMean && diffInRaw <= thresholdForStart) {
                        midPointPosition = readingsForDevice.get(j-1);
                        break;
                    }
                }



                deviceFuelEventMetadata.put(lookupKey, new FuelEventMetadata());

                FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
                fuelEventMetadata.setStartLevel((double) midPointPosition.getAttributes()
                                                                         .get(Position.KEY_CALIBRATED_FUEL_LEVEL));

                fuelEventMetadata.setErrorCheckStart((double) readingsForDevice.get(0)
                                                                               .getAttributes()
                                                                               .get(Position.KEY_CALIBRATED_FUEL_LEVEL));

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

                Position midPointPosition = readingsForDevice.get(0);

                double thresholdForEnd = rightMean > leftMean? 0.05 : -0.05;

                for (int j = midPoint; j > 0; j--) {
                    double diffInRaw = readingsForDevice.get(j).getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL) -
                                       readingsForDevice.get(j-1).getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);

                    if (rightMean > leftMean && diffInRaw >= thresholdForEnd) {
                        midPointPosition = readingsForDevice.get(j);
                        break;
                    } else if (rightMean < leftMean && diffInRaw <= thresholdForEnd) {
                        midPointPosition = readingsForDevice.get(j);
                        break;
                    }
                }

            FuelEventMetadata fuelEventMetadata = deviceFuelEventMetadata.get(lookupKey);
            fuelEventMetadata.setEndLevel((double) midPointPosition.getAttributes()
                                                                   .get(Position.KEY_CALIBRATED_FUEL_LEVEL));
            fuelEventMetadata.setErrorCheckEnd((double) readingsForDevice.get(readingsForDevice.size() - 1)
                                                                         .getAttributes()
                                                                         .get(Position.KEY_CALIBRATED_FUEL_LEVEL));
            fuelEventMetadata.setEndTime(midPointPosition.getDeviceTime());
            fuelEventMetadata.setActivityEndPosition(midPointPosition);

            double fuelChangeVolume = fuelEventMetadata.getEndLevel() - fuelEventMetadata.getStartLevel();
            double errorCheckFuelChange = fuelEventMetadata.getErrorCheckEnd() - fuelEventMetadata.getErrorCheckStart();

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
            Log.debug("[FUEL_ACTIVITY_END] fuelChangeVolume: " + fuelChangeVolume);
            Log.debug("[FUEL_ACTIVITY_END] errorCheckFuelChange: " + errorCheckFuelChange);

            Optional<Long> maxCapacity = Context.getPeripheralSensorManager().getFuelTankMaxCapacity(deviceId, sensorId);
            boolean isDataLoss = FuelDataLossChecker.isFuelEventDueToDataLoss(fuelEventMetadata, maxCapacity);

            if (!isDataLoss && fuelChangeVolume < 0.0) {
                fuelActivity.setActivityType(FuelActivity.FuelActivityType.FUEL_DRAIN);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivityStartPosition(fuelEventMetadata.getActivityStartPosition());
                fuelActivity.setActivityEndPosition(fuelEventMetadata.getActivityEndPosition());
                deviceFuelEventMetadata.remove(lookupKey);
            } else if (fuelChangeVolume > 0.0) {
                fuelActivity.setActivityType(FuelActivity.FuelActivityType.FUEL_FILL);
                fuelActivity.setChangeVolume(fuelChangeVolume);
                fuelActivity.setActivityStartTime(fuelEventMetadata.getStartTime());
                fuelActivity.setActivityEndTime(fuelEventMetadata.getEndTime());
                fuelActivity.setActivityStartPosition(fuelEventMetadata.getActivityStartPosition());
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

    public static Optional<FuelActivity> checkForActivityIfDataLoss(final Position position,
                                                                    final Position lastPosition,
                                                                    final Optional<Long> maxTankMaxVolume) {

        boolean requiredFieldsPresent = FuelDataLossChecker.checkRequiredFieldsPresent(lastPosition, position);
        if (!requiredFieldsPresent) {
            // Not enough info to process data loss.
            return Optional.empty();
        }

        ExpectedFuelConsumption expectedFuelConsumption =
                FuelDataLossChecker.getExpectedFuelConsumptionValues(lastPosition,
                                                                             position,
                                                                             maxTankMaxVolume);

        double calculatedFuelChangeVolume = position.getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL)
                - lastPosition.getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);

        if (Math.abs(calculatedFuelChangeVolume) > expectedFuelConsumption.allowedDeviation) {
            if (calculatedFuelChangeVolume < 0.0) {
                boolean isDataLoss = FuelDataLossChecker.possibleDataLoss(calculatedFuelChangeVolume,
                                                                          expectedFuelConsumption);

                if (isDataLoss) {
                    Log.info(String.format(
                            "Determined data loss, but cannot identify fuel event since calculatedVolume" +
                                    " is outside expected range: %s", expectedFuelConsumption));

                    return Optional.empty();
                }

                if (Math.abs(calculatedFuelChangeVolume) > expectedFuelConsumption.expectedMaxFuelConsumed) {
                    double possibleFuelDrain =
                            Math.abs(calculatedFuelChangeVolume) -
                                    expectedFuelConsumption.expectedCurrentFuelConsumed;
                    FuelActivity activity =
                            new FuelActivity(FuelActivity.FuelActivityType.FUEL_DRAIN,
                                             possibleFuelDrain, lastPosition, position);
                    return Optional.of(activity);
                } else {
                    double possibleFuelFill =
                            expectedFuelConsumption.expectedCurrentFuelConsumed -
                                    Math.abs(calculatedFuelChangeVolume);
                    FuelActivity activity =
                            new FuelActivity(FuelActivity.FuelActivityType.FUEL_FILL,
                                             possibleFuelFill, lastPosition, position);
                    return Optional.of(activity);
                }
            } else {
                double expectedFuelFill =
                        calculatedFuelChangeVolume + expectedFuelConsumption.expectedCurrentFuelConsumed;
                FuelActivity activity =
                        new FuelActivity(FuelActivity.FuelActivityType.FUEL_FILL,
                                         expectedFuelFill, lastPosition, position);
                return Optional.of(activity);
            }
        }

        return Optional.empty();
    }
}
