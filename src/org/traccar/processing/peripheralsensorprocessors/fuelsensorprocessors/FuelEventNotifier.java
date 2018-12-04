package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Event;

import java.sql.SQLException;

import static org.traccar.Context.getDataManager;

public class FuelEventNotifier {
    public static void sendNotificationIfNecessary(final long deviceId, final FuelActivity fuelActivity) {
        if (fuelActivity.getActivityType() != FuelActivity.FuelActivityType.NONE) {
            Log.debug("[FUEL_ACTIVITY]  DETECTED: " + fuelActivity.getActivityType()
                              + " starting at: " + fuelActivity.getActivityStartTime()
                              + " ending at: " + fuelActivity.getActivityEndTime()
                              + " volume: " + fuelActivity.getChangeVolume()
                              + " start lat, long " + fuelActivity.getActivityStartPosition().getLatitude()
                              + ", " + fuelActivity.getActivityStartPosition().getLongitude()
                              + " end lat, long " + fuelActivity.getActivityEndPosition().getLatitude()
                              + ", " + fuelActivity.getActivityEndPosition().getLongitude());

            // Add event to events table
            String eventType =
                    fuelActivity.getActivityType() == FuelActivity.FuelActivityType.FUEL_FILL
                            ? Event.TYPE_FUEL_FILL
                            : Event.TYPE_FUEL_DRAIN;

            Event event = new Event(eventType, deviceId,
                                    fuelActivity.getActivityStartPosition().getId());
            event.set("startTime", fuelActivity.getActivityStartTime().getTime());
            event.set("endTime", fuelActivity.getActivityEndTime().getTime());
            event.set("volume", fuelActivity.getChangeVolume());
            event.set("endPositionId", fuelActivity.getActivityEndPosition().getId());
            event.set("startLat", fuelActivity.getActivityStartPosition().getLatitude());
            event.set("startLong", fuelActivity.getActivityStartPosition().getLongitude());
            event.set("endLat", fuelActivity.getActivityEndPosition().getLatitude());
            event.set("endLong", fuelActivity.getActivityEndPosition().getLongitude());

            try {
                getDataManager().addObject(event);
            } catch (SQLException error) {
                Log.warning("Error while saving fuel event to DB", error);
            }

            Context.getFcmPushNotificationManager().updateFuelActivity(fuelActivity);
        }
    }
}
