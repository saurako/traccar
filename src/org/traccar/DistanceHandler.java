/*
 * Copyright 2015 Amila Silva
 * Copyright 2016 - 2017 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.Log;
import org.traccar.model.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DistanceHandler extends BaseDataHandler {

    private static final long MILLIS_IN_SECOND = 1000L;
    private static final long MAX_DATA_LOSS_DURATION_SECONDS = 1800L;

    private final boolean filter;
    private final int coordinatesMinError;
    private final int coordinatesMaxError;

    public DistanceHandler(boolean filter, int coordinatesMinError, int coordinatesMaxError) {
        this.filter = filter;
        this.coordinatesMinError = coordinatesMinError;
        this.coordinatesMaxError = coordinatesMaxError;
    }

    private Position getLastPosition(long deviceId) {
        if (Context.getIdentityManager() != null) {
            return Context.getIdentityManager().getLastPosition(deviceId);
        }
        return null;
    }

    @Override
    protected Position handlePosition(Position position) {

        // Log position lat, long before we process to debug issue with device 21
        long deviceId = position.getDeviceId();
        if (deviceId == 21) {
            Log.debug("[Device21] Before - lat:" + position.getLatitude() + ", long: " + position.getLongitude());
        }

        double distance = 0.0;
        if (position.getAttributes().containsKey(Position.KEY_DISTANCE)) {
            distance = position.getDouble(Position.KEY_DISTANCE);
        }
        double totalDistance = 0.0;

        Position last = getLastPosition(position.getDeviceId());

        if (last != null && position.getDeviceTime().compareTo((last.getDeviceTime())) >= 0) {
            totalDistance = last.getDouble(Position.KEY_TOTAL_DISTANCE);

            // If the current or last position is not valid (esp when lat=long=0), we want to carry forward the
            // total distance previously recorded, so that calculations on future positions stay correct.
            boolean valid = allValid(position, last);
            if (!valid) {
                position.set(Position.KEY_DISTANCE, 0);
                position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
                return position;
            }

            // Calculate and set distance if it is not already set, AND
            // the last position had ignition ON, indicating a very high probability that the vehicle
            // was actually moving
            if (deviceId == 21) {
                Log.debug("[Device21] last ignition: " + last.getBoolean(Position.KEY_IGNITION));
            }

            if (!position.getAttributes().containsKey(Position.KEY_DISTANCE)
                    && last.getBoolean(Position.KEY_IGNITION)) {
                distance = DistanceCalculator.distance(
                        position.getLatitude(), position.getLongitude(),
                        last.getLatitude(), last.getLongitude());
                distance = BigDecimal.valueOf(distance).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
                if (deviceId == 21) {
                    Log.debug("[Device21] distance first pass: " + distance);
                }
            }

            // If data missing then check odometer readings. Knowing ignition was on may not help in this case, coz we
            // don't know for sure what happened during data loss.
            long durationBetweenPacketsSeconds =
                    (position.getDeviceTime().getTime() - last.getDeviceTime().getTime()) / MILLIS_IN_SECOND;

            if (deviceId == 21) {
                // Bools to use and log:
                boolean durationBool =  durationBetweenPacketsSeconds >= MAX_DATA_LOSS_DURATION_SECONDS;
                boolean lastHasOdo = last.getAttributes().containsKey(Position.KEY_ODOMETER);
                boolean currentHasOdo = position.getAttributes().containsKey(Position.KEY_ODOMETER);
                String logString = String.format("[Device21] durationBool: %b, lastHasOdo: %b, currentHasOdo: %b", durationBool, lastHasOdo, currentHasOdo);
                Log.debug(logString);
            }

            if (durationBetweenPacketsSeconds >= MAX_DATA_LOSS_DURATION_SECONDS
                && last.getAttributes().containsKey(Position.KEY_ODOMETER)
                && position.getAttributes().containsKey(Position.KEY_ODOMETER)) {

                double differenceInOdometer = (double) (Integer) position.getAttributes().get(Position.KEY_ODOMETER)
                                              - (double) (Integer)last.getAttributes().get(Position.KEY_ODOMETER);

                if (deviceId == 21) {
                    boolean diffInOdoBool = differenceInOdometer > distance;
                    Log.debug(String.format("[Device21] diffInOdo: %f, difInOdoBool: %b", differenceInOdometer, diffInOdoBool));
                }

                if(differenceInOdometer > distance) {
                    distance = differenceInOdometer;

                    if (position.getDeviceId() == 21) {
                        Log.debug("[Device21] After 1 - lat:" + position.getLatitude() + ", long: " + position.getLongitude() + " distance: " + distance + " diffInODo: " + differenceInOdometer);
                    }
                }
            }

            if (deviceId == 21) {
                String filterBoolString = String.format("[Device21] filterBool: %b, " +
                                                                " last.getValid(): %b, " +
                                                                " last.getLatitude(): %b, " +
                                                                " coordinatesMinError == 0: %b "+
                                                                " distance > coordinatesMinError: %b, " +
                                                                " distance < coordinatesMaxError : %b, " +
                                                                " position.getValid(): %b " +
                                                                " coordinatesMinError: %d, " +
                                                                " coordinatesMaxError: %d",
                                                        filter,
                                                        last.getValid(),
                                                        last.getLatitude() != 0,
                                                        (coordinatesMinError == 0),
                                                        (distance > coordinatesMinError),
                                                        (distance < coordinatesMaxError),
                                                        position.getValid(),
                                                        coordinatesMinError,
                                                        coordinatesMaxError);

                Log.debug(filterBoolString);
            }


            if (filter && last.getValid() && last.getLatitude() != 0 && last.getLongitude() != 0) {
                boolean satisfiesMin = coordinatesMinError == 0 || distance > coordinatesMinError;
                boolean satisfiesMax = coordinatesMaxError == 0
                        || distance < coordinatesMaxError || position.getValid();
                if (!satisfiesMin || !satisfiesMax) {
                    position.setLatitude(last.getLatitude());
                    position.setLongitude(last.getLongitude());
                    distance = 0;
                    if (position.getDeviceId() == 21) {
                        Log.debug("[Device21] After 2.1 - lat:" + position.getLatitude() + ", long: " + position.getLongitude() + " distance: " + distance);
                    }
                }

                if (position.getDeviceId() == 21) {
                    Log.debug("[Device21] After 2.2 - lat:" + position.getLatitude() + ", long: " + position.getLongitude() + " distance: " + distance);
                }

            }
        }
        position.set(Position.KEY_DISTANCE, distance);
        totalDistance = BigDecimal.valueOf(totalDistance + distance).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);

        if (position.getDeviceId() == 21) {
            Log.debug("[Device21] After 3 - lat:" + position.getLatitude() + ", long: " + position.getLongitude());
        }

        return position;
    }

    private boolean allValid(final Position position, final Position last) {
        return position.getValid()
                && position.getLatitude() != 0.0
                && position.getLongitude() != 0.0
                && last.getValid()
                && last.getLatitude() != 0.0
                && last.getLongitude() != 0.0;
    }
}
