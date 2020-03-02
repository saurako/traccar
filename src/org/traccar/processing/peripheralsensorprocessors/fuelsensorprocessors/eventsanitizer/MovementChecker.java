package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer;

import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;

import java.util.List;

public class MovementChecker implements SanityChecker {

    @Override
    public boolean isTruePositive(FuelActivity fuelActivity,
                                  List<Position> surroundingPositions,
                                  PeripheralSensor fuelSensor) {


    }
}
