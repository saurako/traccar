package org.traccar.processing;

import org.junit.Test;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.*;

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

    }

    private List<Position> generatePositions(int size,
                                             double startFuelLevel,
                                             double endFuelLevel) {

        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Position position = new Position();
            double fuelIncrement = i * ((endFuelLevel - startFuelLevel)/size);
            double fuelValue = startFuelLevel + fuelIncrement;
            position.set(Position.KEY_FUEL_LEVEL, fuelValue);
            position.set(Position.KEY_CALIBRATED_FUEL_LEVEL, fuelValue);
            position.setDeviceTime(getAdjustedTime(30 *(size - i)));
            positions.add(position);
        }

        return positions;
    }

    private Position getPositionWithCalibValue(double value) {
        Position p = new Position();
        p.set(Position.KEY_CALIBRATED_FUEL_LEVEL, value);
        return p;
    }


    public void testOutliers() {
        List<Position> positions = new ArrayList<>();
        positions.add(getPositionWithCalibValue(100.0));
        positions.add(getPositionWithCalibValue(100.2));
        positions.add(getPositionWithCalibValue(100.5));
        positions.add(getPositionWithCalibValue(100.3));
        positions.add(getPositionWithCalibValue(102.0));
        positions.add(getPositionWithCalibValue(100.1));
        positions.add(getPositionWithCalibValue(100.4));
        positions.add(getPositionWithCalibValue(100.2));
        positions.add(getPositionWithCalibValue(100.6));

        boolean isOutlier = FuelSensorDataHandlerHelper.isOutlierPresentInSublist(positions,
                                                              4, Optional.of(100L));

        assert isOutlier == true;
    }

    private Date getAdjustedTime(int secondsBehind) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, -secondsBehind);
        return calendar.getTime();
    }
}