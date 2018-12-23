package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.google.common.collect.Lists;
import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FalseEventChecker {

    private static final double FALSE_ALERT_FUEL_DIFF_THRESHOLD = 0.1;
    private static int WINDOW_SIZE_MILLIS = 3600_000;

    private static Map<Long, List<FuelActivity>> DEVICE_PENDING_ALERTS_LIST = new ConcurrentHashMap<>();

    private FalseEventChecker() {}

    public static void addEventToPendingList(long deviceId, FuelActivity fuelActivity) {
        if (!DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)) {
            List<FuelActivity> pendingList = Lists.newArrayList();
            DEVICE_PENDING_ALERTS_LIST.put(deviceId, pendingList);
        }

        DEVICE_PENDING_ALERTS_LIST.get(deviceId).add(fuelActivity);
    }

    public static int pendingListSize(long deviceId) {
        return DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)? DEVICE_PENDING_ALERTS_LIST.get(deviceId).size() : 0;
    }

    public static void removeEventFromPendingList(long deviceId, int startIndex, int endIndex) {
        if (!DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }
        List<FuelActivity> pendingList = DEVICE_PENDING_ALERTS_LIST.get(deviceId);

        boolean validBoundaries = validateIndices(startIndex, endIndex, pendingList);

        if (!validBoundaries) {
            Log.debug(String.format("Invalid boundaries to remove from pending list start: %d, end: %d, listSize: %d",
                                    startIndex, endIndex, pendingList.size()));
            return;
        }

        for (int i = startIndex; i <= endIndex; i++) {
            pendingList.remove(i);
        }
    }

    private static boolean validateIndices(int startIndex, int endIndex, List pendingList) {
        return startIndex < endIndex
                && startIndex >= 0
                && startIndex < pendingList.size()
                && endIndex < pendingList.size();

    }

    public static void removeEvent(long deviceId, int index) {
        if (!DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }
        List<FuelActivity> pendingList = DEVICE_PENDING_ALERTS_LIST.get(deviceId);

        if (index >= pendingList.size()) {
            Log.debug(String.format("Pending list for device %d is smaller than requested index %d: size = %d",
                                    deviceId, index, pendingList.size()));
            return;
        }

        DEVICE_PENDING_ALERTS_LIST.get(deviceId).remove(index);
    }

    public static void checkAndReportFalseAlerts(Position position) {
        long deviceId = position.getDeviceId();

        if (!DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }

        List<FuelActivity> pendingList = DEVICE_PENDING_ALERTS_LIST.get(deviceId);

        if (position.getDeviceTime().getTime() - pendingList.get(0).getActivityEndTime().getTime() < WINDOW_SIZE_MILLIS) {
            Log.debug(String.format("[DeviceId: %d]: Time difference between first event and current too small",
                                    deviceId));
            return;
        }

        if (pendingList.size() == 1) {
            // Will remove the first item in the pendingList and use that to send an alert.
            FuelEventNotifier.sendNotificationIfNecessary(deviceId, pendingList.remove(0));
            Log.debug("pending list=1");
            return;
        }

        for (int i = 1; i < pendingList.size(); i++) {
            Log.debug("pending list size" + pendingList.size() + " i= " + i);

            double checkFuelLevel = pendingList.get(i).getActivityEndPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);
            double consumedFuel = 0.0;

            for (int j = i-1; j >= 0; j--) {

                FuelActivity startActivity = pendingList.get(j+1);
                FuelActivity endActivity = pendingList.get(j);

                double startLevel = startActivity.getActivityStartPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);
                double endLevel = endActivity.getActivityEndPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);

                double diffInFuelLevels = endLevel - startLevel;
                Log.debug("pending list size" + pendingList.size() + " i= " + i + " j= " + j + "checkFuelLevel-" + checkFuelLevel 
                    + " consumedFuel" + consumedFuel + " startLevel" + startLevel + " endLevel" + endLevel 
                    + " diffInFuelLevels" + diffInFuelLevels);

                if (diffInFuelLevels > 0) {
                    consumedFuel += diffInFuelLevels;
                }

                double minFuelLevel = (checkFuelLevel - consumedFuel)* (1.0 - FALSE_ALERT_FUEL_DIFF_THRESHOLD);
                double maxFuelLevel = (checkFuelLevel - consumedFuel)* (1.0 + FALSE_ALERT_FUEL_DIFF_THRESHOLD);
                Log.debug("pending list size" + pendingList.size() + " i= " + i + " j= " + j + "checkFuelLevel-" + checkFuelLevel 
                    + " consumedFuel" + consumedFuel + " startLevel" + startLevel + " endLevel" + endLevel 
                    + " diffInFuelLevels" + diffInFuelLevels + " minFuelLevel" + minFuelLevel + " maxFuelLevel" + maxFuelLevel
                    + "check endactivity start val-" + endActivity.getActivityStartPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL));

                if (endActivity.getActivityStartPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL) > minFuelLevel
                    && endActivity.getActivityStartPosition().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL) < maxFuelLevel) {
                    Log.debug("possible false");
                    // Possible false alerts
                    for (int a = j; a <= i; a++) {
                        Log.debug("Possible false- a:" + a + " i=" + i + " j=" + j);
                        FuelEventNotifier.sendNotificationIfNecessary(deviceId, pendingList.remove(0), true);
                    }
                    break;
                }
            }
        }

        for (int i = 1; i < pendingList.size() - 1; i++) {

            if (position.getDeviceTime().getTime() - pendingList.get(0).getActivityEndPosition().getDeviceTime().getTime() <= WINDOW_SIZE_MILLIS) {
                Log.debug("continue");
                continue;
            }
            Log.debug("not continue: pendingList size" + pendingList.size());
            FuelEventNotifier.sendNotificationIfNecessary(deviceId, pendingList.remove(0));
        }
    }

    public static void falseAlertsBeyondWindow(Position position) {
        long deviceId = position.getDeviceId();
        Log.debug("in falseAlertsBeyondWindow");
        try {
            Optional<Event> mostRecentFuelEvent = Context.getDataManager().getMostRecentFuelEvent(deviceId);
            mostRecentFuelEvent.ifPresent(event -> handleEventBeyondWindow(event, deviceId));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void handleEventBeyondWindow(Event previousAlert, long deviceId) {

        Log.debug("in handle event beyond window");
        if (!DEVICE_PENDING_ALERTS_LIST.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }

        List<FuelActivity> pendingList = DEVICE_PENDING_ALERTS_LIST.get(deviceId);
        Position fuelActivityEndPosition = pendingList.get(0).getActivityEndPosition();
        Position fuelActivityStartPosition = pendingList.get(0).getActivityStartPosition();

        double activityStartFuelLevel = fuelActivityStartPosition.getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);
        double checkFuelLevel = fuelActivityEndPosition.getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);
        Log.debug("before try");

        try {
            Optional<Position> previousAlertStartPosition = Context.getDataManager().getPositionById(previousAlert.getPositionId());
            Optional<Position> previousAlertEndPosition = Context.getDataManager().getPositionById(previousAlert.getLong("endPositionId"));
            Log.debug("start first-" + previousAlert.getLong("startPositionId"));
            Log.debug("start 2-" + previousAlert.getPositionId());
            Log.debug("end-" + previousAlert.getLong("endPositionId"));

            if (previousAlertStartPosition.isPresent() && previousAlertEndPosition.isPresent()) {
                Log.debug("previous present");
                double previousAlertEndFuelLevel = previousAlertEndPosition.get().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);
                double previousAlertStartFuelLevel = previousAlertStartPosition.get().getDouble(Position.KEY_CALIBRATED_FUEL_LEVEL);

                double consumedFuel = previousAlertEndFuelLevel - activityStartFuelLevel;
                double minFuelLevel = (checkFuelLevel - consumedFuel)* (1.0 - FALSE_ALERT_FUEL_DIFF_THRESHOLD);
                double maxFuelLevel = (checkFuelLevel - consumedFuel)* (1.0 + FALSE_ALERT_FUEL_DIFF_THRESHOLD);

                if (previousAlertStartFuelLevel > minFuelLevel && previousAlertStartFuelLevel < maxFuelLevel) {
                    Log.debug("false event beyond window identified");
                    FuelEventNotifier.sendNotificationIfNecessary(deviceId, pendingList.remove(0), true);
                } else {
                    Log.debug("not a false event beyond window");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
