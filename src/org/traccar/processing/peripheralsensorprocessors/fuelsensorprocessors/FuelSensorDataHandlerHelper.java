package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Position;
import org.traccar.transforms.model.FuelSensorCalibration;
import org.traccar.transforms.model.SensorPointsMap;

import java.util.*;

/**
 * Created by saurako on 8/11/18.
 */
public class FuelSensorDataHandlerHelper {

    public static final double TWO_MULTIPLIER = 2.0;

    public static boolean isOutlierPresentInSublist(List<Position> rawFuelOutlierSublist,
                                                    int indexOfPositionEvaluated, long deviceId, int sensorId) {



        Optional<FuelSensorCalibration> fuelSensorCalibration =
                Context.getPeripheralSensorManager().
                        getDeviceSensorCalibrationData(deviceId, sensorId);

        //if (!fuelSensorCalibration.isPresent()) {
          //  return Optional.empty();
        //}

        // Make a B-tree map of the points to fuel level map
        TreeMap<Long, SensorPointsMap> sensorPointsToVolumeMap =
                new TreeMap<>(fuelSensorCalibration.get().getSensorPointsMap());

        long lastFuelLevelKey = sensorPointsToVolumeMap.lastKey();
        SensorPointsMap lastFuelLevelInfo = sensorPointsToVolumeMap.ceilingEntry(lastFuelLevelKey).getValue();
        long maxFuelInTank = lastFuelLevelInfo.getFuelLevel();
        double allowedDeviation = (double) maxFuelInTank * 0.01;

        // Make a copy so we don't affect the original incoming list esp in the sort below,
        // since the order of the incoming list needs to be preserved to remove / mark the right
        // Position as an outlier.
        List<Position> copyOfRawValues = new ArrayList<>();
        for (Position p : rawFuelOutlierSublist) {
            Position tempPosition = new Position();
            tempPosition.set(Position.KEY_CALIBRATED_FUEL_LEVEL, (double) p.getAttributes().get(Position.KEY_CALIBRATED_FUEL_LEVEL));
            copyOfRawValues.add(tempPosition);
        }

        int listSize = copyOfRawValues.size();

        double sumOfValues =
                copyOfRawValues.stream()
                                     .mapToDouble(p -> (double) p.getAttributes()
                                                                 .get(Position.KEY_CALIBRATED_FUEL_LEVEL))
                                     .sum();

        double mean = sumOfValues / (double) listSize;


        double sumOfSquaredDifferenceOfMean =
                copyOfRawValues.stream()
                                     .mapToDouble(p -> {
                                         double differenceOfMean =
                                                 (double) p.getAttributes()
                                                           .get(Position.KEY_CALIBRATED_FUEL_LEVEL) - mean;
                                         return differenceOfMean * differenceOfMean;
                                     }).sum();



        double rawFuelOfPositionEvaluated =
                (double) copyOfRawValues.get(indexOfPositionEvaluated)
                                              .getAttributes()
                                              .get(Position.KEY_CALIBRATED_FUEL_LEVEL);

        copyOfRawValues.sort(Comparator.comparing(p -> (double) p.getAttributes()
                                                                       .get(Position.KEY_CALIBRATED_FUEL_LEVEL)));

        int midPointOfList = (listSize - 1) / 2;

        double medianRawFuelValue = (double) copyOfRawValues.get(midPointOfList)
                                                                  .getAttributes()
                                                                  .get(Position.KEY_CALIBRATED_FUEL_LEVEL);

        double standardDeviation = Math.sqrt(sumOfSquaredDifferenceOfMean / (double) listSize);
        if ((allowedDeviation / 2) > standardDeviation) {
            standardDeviation = allowedDeviation / 2;
        }

        // 2 standard deviations away
        double lowerBoundOnRawFuelValue = medianRawFuelValue - (TWO_MULTIPLIER * standardDeviation);
        double upperBoundOnRawFuelValue = medianRawFuelValue + (TWO_MULTIPLIER * standardDeviation);

        boolean isOutlier = rawFuelOfPositionEvaluated < lowerBoundOnRawFuelValue
                            || rawFuelOfPositionEvaluated > upperBoundOnRawFuelValue;

        Log.debug("[OUTLIER_STAT] sumOfValues: " + sumOfValues
                  + " mean: " + mean
                  + " sumOfSquaredDifferenceOfMean: " + sumOfSquaredDifferenceOfMean
                  + " rawFuelOfPositionEvaluated: " + rawFuelOfPositionEvaluated
                  + " standardDeviation: " + standardDeviation
                  + " lowerBoundOnRawFuelValue: " + lowerBoundOnRawFuelValue
                  + " upperBoundOnRawFuelValue: " + upperBoundOnRawFuelValue
                  + " isOutlier: " + isOutlier);

        return isOutlier;
    }

    public static boolean isDataLoss(final FuelEventMetadata fuelEventMetadata,
                                     final double calculatedFuelChangeVolume) {


        boolean requiredFieldsPresent = checkRequiredFieldsPresent(fuelEventMetadata);
        if (!requiredFieldsPresent) {
            // Not enough info to process data loss.
            return false;
        }

        double startTotalGPSDistanceInMeters = (double) fuelEventMetadata.getActivityStartPosition()
                                                                         .getAttributes()
                                                                         .get(Position.KEY_TOTAL_DISTANCE);

        double endTotalGPSDistanceInMeters = (double) fuelEventMetadata.getActivityEndPosition()
                                                                       .getAttributes()
                                                                       .get(Position.KEY_TOTAL_DISTANCE);

        int startOdometerInMeters = (int) fuelEventMetadata.getActivityStartPosition()
                                                         .getAttributes().get(Position.KEY_ODOMETER);

        int endOdometerInMeters = (int) fuelEventMetadata.getActivityEndPosition()
                                                         .getAttributes().get(Position.KEY_ODOMETER);

        double differenceTotalDistanceInMeters = endTotalGPSDistanceInMeters - startTotalGPSDistanceInMeters;
        double differenceOdometerInMeters = endOdometerInMeters - startOdometerInMeters;

        double maximumDistanceTravelled = Math.max(differenceTotalDistanceInMeters, differenceOdometerInMeters) / 1000;
        double minimumAverageMileage = 1.5; // This has to be a self learning value
        double expectedFuelConsumed = maximumDistanceTravelled / minimumAverageMileage;

        boolean dataLoss = Math.abs(calculatedFuelChangeVolume) <= expectedFuelConsumed;

        if (dataLoss) {
            Log.debug(String.format("Data Loss: Distance covered %f, Exp fuel consumed: %f, actual fuel consumed: %f",
                    maximumDistanceTravelled, expectedFuelConsumed, calculatedFuelChangeVolume));
        }

        return dataLoss;
    }

    private static boolean checkRequiredFieldsPresent(FuelEventMetadata fuelEventMetadata) {
        return fuelEventMetadata.getActivityStartPosition().getAttributes().containsKey(Position.KEY_TOTAL_DISTANCE)
                && fuelEventMetadata.getActivityStartPosition().getAttributes().containsKey(Position.KEY_ODOMETER)
                && fuelEventMetadata.getActivityEndPosition().getAttributes().containsKey(Position.KEY_TOTAL_DISTANCE)
                && fuelEventMetadata.getActivityEndPosition().getAttributes().containsKey(Position.KEY_ODOMETER);
    }
}
