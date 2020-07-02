package org.traccar;

import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.Position;

import java.util.Optional;

public class IgnitionStateHandler extends BaseDataHandler {

    private static long DATA_LOSS_FOR_IGNITION_MILLIS;
    private static int MIN_DISTANCE;

    enum IGNITION_STATE {
        OFF,
        ON,
        N_A
    }

    static {
        DATA_LOSS_FOR_IGNITION_MILLIS =
                Context.getConfig().getLong("processing.peripheralSensorData.ignitionDataLossThresholdSeconds") * 1000L;
        MIN_DISTANCE =
                Context.getConfig().getInteger("coordinates.minError");
    }

    private static String BATTERY_UPPER_MILLI_VOLTS_THRESHOLD_FIELD_NAME = "ext_volt_upper";
    private static String BATTERY_LOWER_MILLI_VOLTS_THRESHOLD_FIELD_NAME = "ext_volt_lower";

    private static String IGNITION_ON_CHANGE_THRESHOLD = "ign_on_change_threshold";
    private static String IGNITION_OFF_CHANGE_THRESHOLD = "ign_off_change_threshold";
    private static String FLUCTUATION_IN_VOLTAGE_WHEN_ON = "ext_volt_fluctuation_when_on";
    private static String IGN_FROM_GPS = "ign_from_gps";



    @Override
    protected Position handlePosition(Position position) {
        try {
            long deviceId = position.getDeviceId();

            if (Context.getDeviceManager() == null) {
                return position;
            }

            Device device = Context.getDeviceManager().getById(deviceId);

            if (device == null) {
                Log.debug(String.format("Device not found: %d", deviceId));
                return position;
            }

            if (!device.getAttributes().containsKey(BATTERY_UPPER_MILLI_VOLTS_THRESHOLD_FIELD_NAME) ||
                    !device.getAttributes().containsKey(BATTERY_LOWER_MILLI_VOLTS_THRESHOLD_FIELD_NAME)) {
                // Not going to log this coz this will produce way too many logs.
                return position;
            }

            Optional<Integer> maybeCurrentVoltage = getMilliVoltsByProtocol(position);

            if (!maybeCurrentVoltage.isPresent()) {
                Log.debug("Current external voltage not found on position.");
                return position;
            }

            Position lastPosition = Context.getDeviceManager().getLastPosition(deviceId);
            if (lastPosition == null) {
                position.set(Position.KEY_CALCULATED_IGNITION, false);
                initializeMeter(position);
                return position;
            }

            if (lastPosition.getDeviceTime().getTime() > position.getDeviceTime().getTime()) {
                Log.debug(String.format("Back dated payload for calculating ignition for deviceId: %d. Ignoring.", deviceId));
                return position;
            }

            Optional<Integer> lastPositionCurrentVoltage = getMilliVoltsByProtocol(lastPosition);
            if (!lastPositionCurrentVoltage.isPresent()) {
                Log.debug("last external voltage not found on position.");
                return position;
            }

            if (position.getDeviceTime().getTime() - lastPosition.getDeviceTime().getTime() >= DATA_LOSS_FOR_IGNITION_MILLIS) {
                // Reset calc run time values in case of data loss.
                position.set(Position.KEY_CALCULATED_IGNITION, false);
                initializeMeter(position, lastPosition);
                return position;
            }

            // No data loss & we have last position. Calculate ignition state, and then run time.

            int upperChangeThreshold = device.getInteger(IGNITION_ON_CHANGE_THRESHOLD) * 1000;
            int lowerChangeThreshold = device.getInteger(IGNITION_OFF_CHANGE_THRESHOLD) * 1000;

            int upperThreshold = device.getInteger(BATTERY_UPPER_MILLI_VOLTS_THRESHOLD_FIELD_NAME) * 1000;
            int lowerThreshold = device.getInteger(BATTERY_LOWER_MILLI_VOLTS_THRESHOLD_FIELD_NAME) * 1000;

            // TRUE MEANS THAT THERE IS FLUCTUATIONS IN THE EXT VOLTAGE WHEN THE ENGINE IS ON
            // Note: Absent defaults to false.
            boolean fluctuationInVoltageWhenOn = device.getBoolean(FLUCTUATION_IN_VOLTAGE_WHEN_ON);

            // TODO: RAHUL: if between upper and lower, only one value is present, i think we should make both, upper and lower as same
            // TODO: we choose upper or lower?

            double distance = position.getDouble(Position.KEY_DISTANCE);

            // First we check that if change of voltage is higher than the upper threshold or distance travelled is larger than the min distance then the unit is on
            // next if fluctuations in external voltage when unit is not is not present, then if the change of voltage is lower than the lower threshold then the unit is off
            // next if voltage is above the upper ext voltage threshold, then unit is on
            // if it is in between the 2 voltage, then the previous state is carried forward
            // else the voltage is lower then the lower ext voltage threshold, so it is off

            int voltageChangeValue = maybeCurrentVoltage.get() - lastPositionCurrentVoltage.get();
            if (voltageChangeValue > upperChangeThreshold || distance > MIN_DISTANCE) {
                position.set(Position.KEY_CALCULATED_IGNITION, true);
            } else if (!fluctuationInVoltageWhenOn && voltageChangeValue < lowerChangeThreshold) {
                // check for lowerChangeThreshold only if fluctuations in ext voltage when unit is on is false, i.e. voltage is stable
                position.set(Position.KEY_CALCULATED_IGNITION, false);
            } else if (maybeCurrentVoltage.get() > upperThreshold) {
                position.set(Position.KEY_CALCULATED_IGNITION, true);
            } else if ((maybeCurrentVoltage.get() <= upperThreshold
                            && maybeCurrentVoltage.get() >= lowerThreshold)) {
                // Carry forward the previous value, if present.
                if (lastPosition.getAttributes().containsKey(Position.KEY_CALCULATED_IGNITION)) {
                    position.set(Position.KEY_CALCULATED_IGNITION, lastPosition.getBoolean(Position.KEY_CALCULATED_IGNITION));
                }

            } else if (maybeCurrentVoltage.get() < lowerThreshold) {
                position.set(Position.KEY_CALCULATED_IGNITION, false);
            }

            // We check if we are supposed to take into account ignition changes resulted from events generated due to change
            // in ignition state from the wire connection to gps in the vehicle. If yes, we check the event code generated.
            // We compare this ignition state with the ignition value already calculated in the current position packet.
            // If both states are same, then nothing required to be done, else change the value.

            boolean useIgnitionStatusFromWireConnection = device.getBoolean(IGN_FROM_GPS);
            if (useIgnitionStatusFromWireConnection) {
                IGNITION_STATE ignitionStateFromCurrentEventCode = getIgnitionStatusFromCurrentEventCode(position);
                if (ignitionStateFromCurrentEventCode != IGNITION_STATE.N_A) {
                    boolean currentlyCalculatedIgnition = position.getBoolean(Position.KEY_CALCULATED_IGNITION);
                    boolean ignitionState = ignitionStateFromCurrentEventCode == IGNITION_STATE.ON; // true IF == ON ELSE false

                    // Reset if they are different
                    if (currentlyCalculatedIgnition ^ ignitionState) {
                        position.set(Position.KEY_CALCULATED_IGNITION, ignitionState);
                    }
                }
            }

            determineRunTimeFromState(position, lastPosition);
        } catch (Exception e) {
            Log.info("Exception while calculating ignition state.");
            e.printStackTrace();
        }
        return position;
    }

    private IGNITION_STATE getIgnitionStatusFromCurrentEventCode(Position position) {
        int currentEventCode = position.getInteger(Position.KEY_EVENT);

        // For itriangle, code 110 is generated when vehicle switched on and 111 generated when swithced off
        // For teltonika event 239 is generated when a change of state happens. So we check that for instances when
        // 239 is generated, the default ignition value. If it is true then a change of state from off to on has happened, if false, then vice versa.
        int ignitionStatusFromCurrentEventCode = 2;
        switch (position.getProtocol().toLowerCase()) {
            case "aquila":
                if (currentEventCode == 110) {
                    return IGNITION_STATE.ON;
                }

                if (currentEventCode == 111) {
                    return IGNITION_STATE.OFF;
                }
                break;

            case "teltonika":
                if (currentEventCode == 239 && position.getBoolean(Position.KEY_IGNITION)) {
                    return IGNITION_STATE.ON;
                }

                if (currentEventCode == 239 && !position.getBoolean(Position.KEY_IGNITION)) {
                    return IGNITION_STATE.OFF;
                }
                break;
            default:
                Log.debug("Unknown protocol, not attempting to read event codes.");
        }

        return IGNITION_STATE.N_A;
    }

    private void determineRunTimeFromState(Position position, Position lastPosition) {

        if (!lastPosition.getAttributes().containsKey(Position.KEY_CALCULATED_IGNITION)) {
            initializeMeter(position);
        }

        boolean previousState = lastPosition.getBoolean(Position.KEY_CALCULATED_IGNITION);
        boolean currenState = position.getBoolean(Position.KEY_CALCULATED_IGNITION);

        if (currenState && !previousState) {
            // Start hour meter
            initializeMeter(position, lastPosition);
        } else if (!currenState && previousState) {
            // Continue hour meter, last increment
            continueRunningMeter(position, lastPosition);
        } else if (currenState && previousState) { // Has remained on.
            // Keep the hour meter running
            continueRunningMeter(position, lastPosition);
        } else if (!currenState && !previousState) {

            // Carry 0s over from last position.
            initializeMeter(position, lastPosition);
        }
    }

    private void initializeMeter(Position position) {
        position.set(Position.KEY_CALC_IGN_ON_MILLIS, 0L);
        position.set(Position.KEY_TOTAL_CALC_IGN_ON_MILLIS, 0L);
    }

    private void initializeMeter(final Position position, final Position lastPosition) {
        long totalHours = lastPosition.getLong(Position.KEY_TOTAL_CALC_IGN_ON_MILLIS);
        position.set(Position.KEY_CALC_IGN_ON_MILLIS, 0L);
        position.set(Position.KEY_TOTAL_CALC_IGN_ON_MILLIS, totalHours);
    }

    private void continueRunningMeter(final Position position, final Position lastPosition) {

        long millisIgnOn = position.getDeviceTime().getTime() - lastPosition.getDeviceTime().getTime();
        long totalMillisIgnOn = lastPosition.getLong(Position.KEY_TOTAL_CALC_IGN_ON_MILLIS) + millisIgnOn;

        position.set(Position.KEY_CALC_IGN_ON_MILLIS, millisIgnOn);
        position.set(Position.KEY_TOTAL_CALC_IGN_ON_MILLIS, totalMillisIgnOn);
    }

    private Optional<Integer> getMilliVoltsByProtocol(Position position) {
        Number currentVoltage = (Number) position.getAttributes().get(Position.KEY_POWER); // External battery voltage.

        switch (position.getProtocol().toLowerCase()) {
            case "aquila":
                return Optional.of(currentVoltage.intValue()); // Is already in milli volts
            case "teltonika":
                return Optional.of( (int) (currentVoltage.floatValue() * 1000)); // Convert from volts to milli volts
            default:
                Log.debug("Unknown protocol, not attempting to read external battery voltage values.");
        }

        return Optional.empty();
    }
}
