package org.traccar.processing;

import org.junit.Test;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelSensorDataHandler;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelEventMetadata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FuelSensorDataHandlerTest {


    public void testFuelFillActivity() {

        long sensorId = 1;

        FuelSensorDataHandler fuelSensorDataHandler =
                new FuelSensorDataHandler(false);

        // Fuel is getting consumed before and after we fill.
        List<Position> deviceBeforeFillPositions = generatePositions(10, 40, 30);
        List<Position> deviceFillPositions = generatePositions(10, 30, 70);
        List<Position> deviceAfterFillPositions = generatePositions(10, 70, 65);

        deviceBeforeFillPositions.addAll(deviceFillPositions);
        deviceBeforeFillPositions.addAll(deviceAfterFillPositions);

        Map<String, FuelEventMetadata> fuelEventMetadataMap = new ConcurrentHashMap<>();

        double threshold = 5.34;
        double fuelErrorThreshold = 0.75;

        List<FuelActivity> activities = new LinkedList<>();
        for (int start = 0, end = 9; end < deviceBeforeFillPositions.size(); start++, end++) {
            List<Position> subListToPass = deviceBeforeFillPositions.subList(start, end);
            activities.add(fuelSensorDataHandler.checkForActivity(subListToPass, fuelEventMetadataMap, sensorId, threshold, fuelErrorThreshold));
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

        FuelSensorDataHandler fuelSensorDataHandler =
                new FuelSensorDataHandler(false);

        // Fuel is getting consumed before and after we fill.
        List<Position> deviceBeforeDrainPositions = generatePositions(20, 80, 60);
        List<Position> deviceDrainPositions = generatePositions(10, 60, 50);
        List<Position> deviceAfterDrainPositions = generatePositions(20, 50, 45);

        deviceBeforeDrainPositions.addAll(deviceDrainPositions);
        deviceBeforeDrainPositions.addAll(deviceAfterDrainPositions);

        Map<String, FuelEventMetadata> fuelEventMetadataMap = new ConcurrentHashMap<>();

        double threshold = 3;
        double fuelErrorThreshold = 0.5;

        List<FuelActivity> activities = new LinkedList<>();
        for (int start = 0, end = 9; end < deviceBeforeDrainPositions.size(); start++, end++) {
            List<Position> subListToPass = deviceBeforeDrainPositions.subList(start, end);
            activities.add(fuelSensorDataHandler.checkForActivity(subListToPass, fuelEventMetadataMap, sensorId, threshold, fuelErrorThreshold));
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