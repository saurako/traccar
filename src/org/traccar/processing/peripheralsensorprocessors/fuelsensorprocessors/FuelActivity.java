package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.Date;

public class FuelActivity {

    public FuelActivity() { }

    public FuelActivity(FuelActivityType activityType,
                        double changeVolume,
                        Position activityStartPosition,
                        Position activityEndPosition) {

        this.activityType = activityType;
        this.changeVolume = changeVolume;
        this.activityStartTime = activityStartPosition.getDeviceTime();
        this.activityEndTime = activityEndPosition.getDeviceTime();
        this.activityStartPosition = activityStartPosition;
        this.activityEndPosition = activityEndPosition;
    }

    public enum FuelActivityType {
        NONE("none"),
        FUEL_FILL(Event.TYPE_FUEL_FILL),
        FUEL_DRAIN(Event.TYPE_FUEL_DRAIN),
        PROBABLE_FUEL_FILL(Event.TYPE_PROBABLE_FILL),
        EXPECTED_FUEL_FILL(Event.TYPE_EXPECTED_FILL),
        PROBABLE_FUEL_DRAIN(Event.TYPE_PROBABLE_DRAIN),
        DRAIN_WITHIN_CONSUMPTION(Event.TYPE_DRAIN_WITHIN_CONSUMPTION),
        DRAIN_DUE_TO_NOISE(Event.TYPE_DRAIN_DUE_TO_NOISE),
        FILL_DUE_TO_NOISE(Event.TYPE_FILL_DUE_TO_NOISE);


        private String nameString;

        FuelActivityType(String name) {
            nameString = name;
        }

        @Override
        public String toString() {
            return nameString;
        }
    }

    private FuelActivityType activityType = FuelActivityType.NONE;
    private double changeVolume = 0;
    private Date activityStartTime;
    private Date activityEndTime;
    private Position activityStartPosition;
    private Position activityEndPosition;
    private String falsePositiveReason;

    public FuelActivityType getActivityType() {
        return activityType;
    }

    public void setActivityType(FuelActivityType activityType) {
        this.activityType = activityType;
    }

    public double getChangeVolume() {
        return changeVolume;
    }

    public void setChangeVolume(double amount) {

        this.changeVolume = Math.abs(amount);
    }

    public Date getActivityStartTime() {
        return activityStartTime;
    }

    public void setActivityStartTime(Date activityStartTime) {
        this.activityStartTime = activityStartTime;
    }

    public Date getActivityEndTime() {
        return activityEndTime;
    }

    public void setActivityEndTime(Date activityEndTime) {
        this.activityEndTime = activityEndTime;
    }

    public Position getActivityStartPosition() {
        return activityStartPosition;
    }

    public void setActivityStartPosition(final Position activitystartPosition) {
        this.activityStartPosition = activitystartPosition;
    }

    public Position getActivityEndPosition() {
        return activityEndPosition;
    }

    public void setActivityEndPosition(final Position activityEndPosition) {
        this.activityEndPosition = activityEndPosition;
    }

    public String getFalsePositiveReason() {
        return falsePositiveReason;
    }

    public void setFalsePositiveReason(String falsePositiveReason) {
        this.falsePositiveReason = falsePositiveReason;
    }

    public boolean isDrain() {
        return activityType == FuelActivityType.FUEL_DRAIN
                || activityType == FuelActivityType.PROBABLE_FUEL_DRAIN;
    }

    public boolean isFill() {
        return activityType == FuelActivityType.FUEL_FILL
                || activityType == FuelActivityType.PROBABLE_FUEL_FILL;
    }
}
