package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer;

import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelConsumptionChecker;

import java.util.List;

public class ConsumptionChecker implements SanityChecker {
    @Override
    public boolean isTruePositive(FuelActivity fuelActivity,
                                  List<Position> surroundingPositions,
                                  PeripheralSensor fuelSensor) {

        boolean isDrain = fuelActivity.getActivityType() == FuelActivity.FuelActivityType.FUEL_DRAIN
                || fuelActivity.getActivityType() == FuelActivity.FuelActivityType.PROBABLE_FUEL_DRAIN;

        if (!isDrain) {
            return true; // Pass on any non drain event
        }

        boolean isTruePositive =
                FuelConsumptionChecker.isFuelConsumptionAsExpected(fuelActivity, allowedDeviation, fuelSensor);

        // Not a true positive drain if consumption is as expected
        if (!isTruePositive) {
            fuelActivity.setFalsePositiveReason("consumption");
            fuelActivity.setActivityType(FuelActivity.FuelActivityType.DRAIN_WITHIN_CONSUMPTION);
        }

        return isTruePositive;
    }
}
