package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.eventsanitizer;

import org.traccar.model.PeripheralSensor;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;

import java.util.List;

public interface SanityChecker {
    boolean isTruePositive(FuelActivity fuelActivity, List<Position> surroundingPositions, PeripheralSensor fuelSensor);
}
