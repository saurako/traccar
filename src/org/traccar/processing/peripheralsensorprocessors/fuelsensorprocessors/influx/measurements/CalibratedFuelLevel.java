/*
 * Copyright (c) 2020 - Present. OrangeCells Lab [www.orangecells.com]
 */

package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements;

import com.influxdb.annotations.Column;
import com.influxdb.annotations.Measurement;

import java.time.Instant;

@Measurement(name="CalibratedFuelLevel")
public class CalibratedFuelLevel {

    @Column(timestamp = true)
    public Instant deviceTime;

    @Column
    public Double milliLiters;
}
