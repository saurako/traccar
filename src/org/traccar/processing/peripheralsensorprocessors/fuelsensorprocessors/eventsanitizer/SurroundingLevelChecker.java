package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer;

import org.traccar.Context;
import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelConsumptionChecker;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public class SurroundingLevelChecker implements SanityChecker {


    private static final int SURROUNDING_EVENTS_SIZE = 5;

    @Override
    public boolean isTruePositive(FuelActivity fuelActivity,
                                  List<Position> surroundingPositions,
                                  PeripheralSensor fuelSensor) {

        int startIndex = surroundingPositions.indexOf(fuelActivity.getActivityStartPosition());
        int endIndex = surroundingPositions.indexOf(fuelActivity.getActivityEndPosition());

        String calibField = fuelSensor.getCalibFuelFieldName();

        int cutOffIndex = (endIndex + SURROUNDING_EVENTS_SIZE) < surroundingPositions.size()? (endIndex + SURROUNDING_EVENTS_SIZE) : surroundingPositions.size();
        List<Position> beforeStart = surroundingPositions.subList(startIndex - SURROUNDING_EVENTS_SIZE, startIndex + 1);
        List<Position> afterEnd = surroundingPositions.subList(endIndex, cutOffIndex);

        OptionalDouble avgBeforeStart = beforeStart.stream()
                                                   .mapToDouble(p -> p.getDouble(calibField))
                                                   .average();
        OptionalDouble avgAfterEnd  = afterEnd.stream().mapToDouble(p -> p.getDouble(calibField))
                                              .average();

        double diffInLevels = Math.abs(avgAfterEnd.orElse(0.0) - avgBeforeStart.orElse(0.0)) ;

        boolean isTruePositive =
                FuelConsumptionChecker.isFuelConsumptionAsExpected(beforeStart.get(0), afterEnd.get(afterEnd.size() - 1), diffInLevels, fuelSensor);

        // Not a true positive drain if consumption is as expected
        if (!isTruePositive) {
            fuelActivity.setFalsePositiveReason("consumption");
            fuelActivity.setActivityType(FuelActivity.FuelActivityType.DRAIN_WITHIN_CONSUMPTION);
        }

        return isTruePositive;
    }
}
