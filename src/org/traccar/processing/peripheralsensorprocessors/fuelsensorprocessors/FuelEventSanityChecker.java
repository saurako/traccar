package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.google.common.collect.Lists;
import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer.ConsumptionChecker;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer.SurroundingLevelChecker;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer.SanityChecker;

import java.util.List;

public class FuelEventSanityChecker {

    private static List<SanityChecker> sanityCheckers = Lists.newArrayList();

    static {
        sanityCheckers.add(new SurroundingLevelChecker());
        sanityCheckers.add(new ConsumptionChecker());
    }

    public boolean isTruePositive(FuelActivity fuelActivity,
                                  List<Position> surroundingPositions,
                                  PeripheralSensor fuelSensor) {

        // All checkers in this loop must say that the event is a truePositive for it to be considered truePositive.
        for (SanityChecker sanityChecker : sanityCheckers) {
           boolean isTruePositive = sanityChecker.isTruePositive(fuelActivity, surroundingPositions, fuelSensor);
           if (!isTruePositive) {
               return false; // short circuit on the first failing check.
           }
        }

        return true;
    }
}
