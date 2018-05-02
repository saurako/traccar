package org.traccar.processing;

import org.junit.Test;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.FuelActivity;
import org.traccar.processing.peripheralsensorprocessors.PeripheralSensorDataHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PeripheralSensorDataHandlerTest {

    public void testFuelFillActivity() {

        long sensorId = 1;
        PeripheralSensorDataHandler peripheralSensorDataHandler =
                new PeripheralSensorDataHandler();

        // Fuel is getting consumed before and after we fill.
        List<Position> deviceBeforeFillPositions = generatePositions(10, 40, 30);
        List<Position> deviceFillPositions = generatePositions(10, 30, 70);
        List<Position> deviceAfterFillPositions = generatePositions(10, 70, 65);

        deviceBeforeFillPositions.addAll(deviceFillPositions);
        deviceBeforeFillPositions.addAll(deviceAfterFillPositions);

        Map<String, PeripheralSensorDataHandler.FuelEventMetadata> fuelEventMetadataMap = new ConcurrentHashMap<>();

        double threshold = 5.31;

        List<FuelActivity> activities = new LinkedList<>();
        for (int start = 0, end = 9; end < deviceBeforeFillPositions.size(); start++, end++) {
            List<Position> subListToPass = deviceBeforeFillPositions.subList(start, end);
            activities.add(peripheralSensorDataHandler.checkForActivity(subListToPass, fuelEventMetadataMap, sensorId, threshold));
        }

        int fuelFills = 0;
        for (FuelActivity activity : activities) {
            if (activity.getActivityType() == FuelActivity.FuelActivityType.FUEL_FILL) {
                fuelFills++;
            }
        }

        assert fuelFills == 1;
    }

    public void testFuelDrainActivity() {
        long sensorId = 1;
        PeripheralSensorDataHandler peripheralSensorDataHandler =
                new PeripheralSensorDataHandler();

        // Fuel is getting consumed before and after we fill.
        List<Position> deviceBeforeDrainPositions = generatePositions(20, 80, 60);
        List<Position> deviceDrainPositions = generatePositions(10, 60, 50);
        List<Position> deviceAfterDrainPositions = generatePositions(20, 50, 55);

        deviceBeforeDrainPositions.addAll(deviceDrainPositions);
        deviceBeforeDrainPositions.addAll(deviceAfterDrainPositions);

        Map<String, PeripheralSensorDataHandler.FuelEventMetadata> fuelEventMetadataMap = new ConcurrentHashMap<>();

        double threshold = 3.00;

        List<FuelActivity> activities = new LinkedList<>();
        for (int start = 0, end = 9; end < deviceBeforeDrainPositions.size(); start++, end++) {
            List<Position> subListToPass = deviceBeforeDrainPositions.subList(start, end);
            activities.add(peripheralSensorDataHandler.checkForActivity(subListToPass, fuelEventMetadataMap, sensorId, threshold));
        }

        int fuelDrains = 0;
        for (FuelActivity activity : activities) {
            if (activity.getActivityType() == FuelActivity.FuelActivityType.FUEL_DRAIN) {
                fuelDrains++;
            }
        }

        assert fuelDrains == 1;
    }

    private List<Position> generatePositions(int size,
                                             double startFuelLevel,
                                             double endFuelLevel) {

        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Position position = new Position();
            double fuelIncrement = i * ((endFuelLevel - startFuelLevel)/size);
            position.set(Position.KEY_FUEL_LEVEL, startFuelLevel + fuelIncrement);
            position.setDeviceTime(getAdjustedTime(30 *(size - i)));
            positions.add(position);
        }

        return positions;
    }

    private Date getAdjustedTime(int secondsBehind) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, -secondsBehind);
        return calendar.getTime();
    }
}