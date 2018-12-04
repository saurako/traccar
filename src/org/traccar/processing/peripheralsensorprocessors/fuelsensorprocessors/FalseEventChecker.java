package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.google.common.collect.Lists;
import org.traccar.helper.Log;
import org.traccar.model.Position;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FalseEventChecker {

    private static int WINDOW_SIZE_MILLIS = 600_000;

    private static FalseEventChecker falseEventCheckerInstance = null;

    private static Map<Long, List<FuelActivity>> DEVICE_ALERT_PENDING_MAP = new ConcurrentHashMap<>();

    private FalseEventChecker() {}

    public static void addEvent(long deviceId, FuelActivity fuelActivity) {
        if (!DEVICE_ALERT_PENDING_MAP.containsKey(deviceId)) {
            List<FuelActivity> pendingList = Lists.newArrayList();
            DEVICE_ALERT_PENDING_MAP.put(deviceId, pendingList);
        }

        DEVICE_ALERT_PENDING_MAP.get(deviceId).add(fuelActivity);
    }

    public static int pendingListSize(long deviceId) {
        return DEVICE_ALERT_PENDING_MAP.containsKey(deviceId)? DEVICE_ALERT_PENDING_MAP.get(deviceId).size() : 0;
    }

    public static void removeEvents(long deviceId, int startIndex, int endIndex) {
        if (!DEVICE_ALERT_PENDING_MAP.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }
        List<FuelActivity> pendingList = DEVICE_ALERT_PENDING_MAP.get(deviceId);

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
        if (!DEVICE_ALERT_PENDING_MAP.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }
        List<FuelActivity> pendingList = DEVICE_ALERT_PENDING_MAP.get(deviceId);

        if (index >= pendingList.size()) {
            Log.debug(String.format("Pending list for device %d is smaller than requested index %d: size = %d",
                                    deviceId, index, pendingList.size()));
            return;
        }

        DEVICE_ALERT_PENDING_MAP.get(deviceId).remove(index);
    }

    public void checkFalseAlert(Position position) {
        long deviceId = position.getDeviceId();

        if (!DEVICE_ALERT_PENDING_MAP.containsKey(deviceId)) {
            Log.debug(String.format("Device %d does not have a pending events list yet", deviceId));
            return;
        }

        List<FuelActivity> pendingList = DEVICE_ALERT_PENDING_MAP.get(deviceId);

        if (position.getDeviceTime().getTime() - pendingList.get(0).getActivityEndTime().getTime() < WINDOW_SIZE_MILLIS) {
            Log.debug(String.format("[DeviceId: %d]: Time difference between first event and current too small",
                                    deviceId));
            return;
        }

        if (pendingList.size() == 1) {
            FuelEventNotifier.sendNotificationIfNecessary(deviceId, pendingList.remove(0));
            return;
        }



    }
}
