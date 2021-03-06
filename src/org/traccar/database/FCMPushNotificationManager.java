package org.traccar.database;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.traccar.Context;
import org.traccar.fcm.PushNotifications;
import org.traccar.helper.Log;
import org.traccar.model.*;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.FuelActivity.FuelActivityType;

import java.sql.SQLException;

import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class FCMPushNotificationManager extends ExtendedObjectManager<FCMPushNotification> {

    private static final int GENERIC_EVENT_TTL =
            Context.getConfig().getInteger("fcm.event.generic.ttl");

    private static final int FUEL_EVENT_TTL =
            Context.getConfig().getInteger("fcm.event.fuel.ttl");

    private final Map<Long, Map<Long, Set<Long>>> fcmNotificationsMap = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> deviceIdToUniqueEventIdMap = new ConcurrentHashMap<>();

    public FCMPushNotificationManager(DataManager dataManager) {
        super(dataManager, FCMPushNotification.class);
        refreshFCMNotificationsMap();
    }

    public void refreshFCMNotificationsMap() {

        try {

            fcmNotificationsMap.clear();

            Collection<FCMPushNotification> fcmPushNotifications =
                    Context.getDataManager().getFCMPushNotifications();

            for (FCMPushNotification fcmPushNotification : fcmPushNotifications) {
                long deviceId = fcmPushNotification.getDeviceId();
                long userId = fcmPushNotification.getUserId();

                if (fcmPushNotification.getEnabled()) {
                    if (!fcmNotificationsMap.containsKey(deviceId)) {
                        fcmNotificationsMap.put(deviceId, new ConcurrentHashMap<>());
                    }

                    Map<Long, Set<Long>> usersOfDevice = fcmNotificationsMap.get(deviceId);

                    if (!usersOfDevice.containsKey(userId)) {
                        usersOfDevice.put(userId, new ConcurrentHashSet<>());
                    }

                    usersOfDevice.get(userId).add(fcmPushNotification.getEventTypeId());

                    if (!deviceIdToUniqueEventIdMap.containsKey(deviceId)) {
                        deviceIdToUniqueEventIdMap.put(deviceId, new ConcurrentHashSet<>());
                    }

                    deviceIdToUniqueEventIdMap.get(deviceId).add(fcmPushNotification.getEventTypeId());
                }
            }
        } catch (SQLException error) {
            Log.warning(error);
        }
    }

    public boolean deviceHasNotificationTypeEnabled(long deviceId, String eventType) {

        if (deviceIdToUniqueEventIdMap.containsKey(deviceId)) {
            Map<String, Long> typeToIdMap = FCMPushNotificationTypeManager.getFCMPushNotificationStringToIdMap();
            if (typeToIdMap.containsKey(eventType)) {
                long eventId = typeToIdMap.get(eventType);
                Set<Long> deviceEvents = deviceIdToUniqueEventIdMap.get(deviceId);
                return deviceEvents.contains(eventId);
            }
        }

        return false;
    }

    private Optional<Set<Long>> getFCMDeviceNotificationForUser(long deviceId, long userId) {
        if (fcmNotificationsMap.containsKey(deviceId)) {
            Map<Long, Set<Long>> usersOfDevice = fcmNotificationsMap.get(deviceId);

            if (usersOfDevice.containsKey(userId)) {
                return Optional.of(usersOfDevice.get(userId));
            }
        }
        return Optional.empty();
    }

    public void updateGenericEvents(Map<Event, Position> events) {
        for (Entry<Event, Position> event : events.entrySet()) {
            updateGenericEvent(event.getKey(), GENERIC_EVENT_TTL);
        }
    }

    public void updateGenericEvent(Event event, int ttl) {
        String eventType = event.getType();

        if (StringUtils.isBlank(eventType)) {
            return;
        }

        long deviceId = event.getDeviceId();
        Set<String> tokens = getEffectiveFCMTokens(eventType, deviceId);

        if (tokens.isEmpty()) {
            return;
        }

        Device device = Context.getDeviceManager().getById(deviceId);
        long startTime = event.getServerTime().getTime();
        if (event.getAttributes().containsKey("startTime")) {
            startTime = (long) event.getAttributes().get("startTime");
        }
        String startTimeString = getDateTimeStringInTimezone(startTime);
        String title = String.format("%s (%s)", device.getName(), device.getRegistrationNumber());
        String body = String.format("[%s]: Vehicle %s", startTimeString,
                                    FCMPushNotificationTypeManager.getFcmPushNotificationTypeToStringMap()
                                                                  .get(eventType));

        PushNotifications.getInstance().sendEventNotification(tokens, title, body, ttl);
    }

    public void updateFuelActivity(FuelActivity fuelActivity, long peripheralSensorId) {

        FuelActivityType eventType = fuelActivity.getActivityType();
        if (eventType == FuelActivityType.NONE) {
            return;
        }

        long deviceId = fuelActivity.getActivityStartPosition().getDeviceId();
        Set<String> tokens = getEffectiveFCMTokens(eventType.name(), deviceId);

        if (tokens.isEmpty()) {
            return;
        }

        Device device = Context.getDeviceManager().getById(deviceId);

        Optional<List<Long>> sortedSensorIds = Context.getPeripheralSensorManager().getSortedSensorIds(deviceId);
        StringBuilder tankIndexBuilder = new StringBuilder("detected on");
        if (sortedSensorIds.isPresent() && sortedSensorIds.get().size() > 1 && sortedSensorIds.get().contains(peripheralSensorId)) {
            tankIndexBuilder.append(String.format(" tank %d of", (sortedSensorIds.get().indexOf(peripheralSensorId) + 1)));
        }

        String title = String.format("[%s] %s %s", eventType, tankIndexBuilder, device.getRegistrationNumber());

        DecimalFormat formatFuelLevel = new DecimalFormat(".#");

        String volumeChanged = formatFuelLevel.format(fuelActivity.getChangeVolume());
        String startTime = getDateTimeStringInTimezone(fuelActivity.getActivityStartTime());
        String endTime = getDateTimeStringInTimezone(fuelActivity.getActivityEndTime());

        String messageBody = String.format("Volume: %sL, %n", volumeChanged)
                             + String.format("Time range: %s - %s", startTime, endTime);

        PushNotifications.getInstance().sendEventNotification(tokens, title, messageBody, FUEL_EVENT_TTL);
    }

    private String getDateTimeStringInTimezone(Long dateInMilliseconds) {
        Date date = new Date(dateInMilliseconds);
        return getDateTimeStringInTimezone(date);
    }

    private String getDateTimeStringInTimezone(Date date) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern("dd-MM-yyyy HH:mm:ss")
                                                            .withZone(DateTimeZone.forID("Asia/Kolkata"));

        return dateTimeFormatter.print(new DateTime(date));
    }

    private ConcurrentHashSet<String> getEffectiveFCMTokens(String eventType, long deviceId) {
        refreshFCMNotificationsMap();

        Map<String, Long> fcmNotificationTypes = FCMPushNotificationTypeManager.getFCMPushNotificationStringToIdMap();
        ConcurrentHashSet<String> tokens = new ConcurrentHashSet<>();
        Set<Long> userIds = Context.getPermissionsManager().getDeviceUsers(deviceId);
        Log.debug("FCM Push notification - device users list: " + userIds.size());

        for (long userId : userIds) {
            Optional<Set<Long>> fcmNotificationsForUser = getFCMDeviceNotificationForUser(deviceId, userId);
            if (fcmNotificationsForUser.isPresent()) {
                long currentNotificationTypeId = fcmNotificationTypes.get(eventType);
                if (fcmNotificationsForUser.get().contains(currentNotificationTypeId)) {
                    Context.getFcmUserTokenManager()
                           .getFCMUserToken(userId)
                           .ifPresent(tokens::add);
                }
            }
        }

        Log.debug("FCM Push notification - activated users list: " + tokens.size());
        return tokens;
    }
}
