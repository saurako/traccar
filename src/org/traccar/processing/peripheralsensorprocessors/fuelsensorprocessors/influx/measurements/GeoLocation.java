/*
 * Copyright (c) 2020 - Present. OrangeCells Lab [www.orangecells.com]
 */

package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements;

import com.influxdb.annotations.Column;
import com.influxdb.annotations.Measurement;

import java.time.Instant;

@Measurement(name="GeoLocation")
public class GeoLocation {
    @Column(timestamp = true)
    public Instant deviceTime;

    @Column
    public Double latitude;

    @Column
    public Double longitude;

    @Column
    public Double distanceMeters;

    @Column
    public Double totalDistanceMeters;

    public GeoLocation() {}
}
