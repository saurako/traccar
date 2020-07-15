package org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.exceptions.NotFoundException;
import jersey.repackaged.com.google.common.collect.Lists;
import jersey.repackaged.com.google.common.collect.Sets;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.traccar.BaseDataHandler;
import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements.CalibratedFuelLevel;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements.ExternalBatteryVoltage;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements.GeoLocation;
import org.traccar.processing.peripheralsensorprocessors.fuelsensorprocessors.influx.measurements.InternalBatteryVoltage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InfluxStreamHandler extends BaseDataHandler {


    private final static String RETENTION_POLICY;
    public static final String INFLUX_USER;
    public static final String INFLUX_PASSWORD;
    public static final String INFLUX_HOST_URL;

    public static final Map<String, InfluxDBClient> CLIENTS_MAP = new ConcurrentHashMap<>();

    static {
        RETENTION_POLICY = Context.getConfig().getString("processing.influx.retentionPolicy");
        INFLUX_USER = Context.getConfig().getString("processing.influx.user");
        INFLUX_PASSWORD = Context.getConfig().getString("processing.influx.password");
        INFLUX_HOST_URL = String.format("%s://%s:%s",
                                        Context.getConfig().getString("processing.influx.protocol"),
                                        Context.getConfig().getString("processing.influx.host"),
                                        Context.getConfig().getInteger("processing.influx.port"));


    }

    @Override
    protected Position handlePosition(final Position position) {
        String deviceIdsToSend = Context.getConfig().getString("processing.influx.deviceids");

        if (StringUtil.isNotBlank(deviceIdsToSend)) {
            Set<Long> setIds = Arrays.asList(deviceIdsToSend.trim().split(","))
                                        .stream()
                                        .map(Long::parseLong).collect(Collectors.toSet());

            if (setIds.contains(position.getDeviceId())) {
                try {
                    writeToInflux(position);
                } catch (Exception e) {
                    Log.debug(String.format("[Influx] Exception while writing to influx: %s", e.getMessage()));
                }
            } else {
                Log.debug(String.format("[Influx] Device id %d not included to send to Influx. Skipping.", position.getDeviceId()));
            }
        } else {
            Log.debug("No deviceIds found to send to Influx. Skipping.");
        }

        return position;
    }

    @NotNull
    private void writeToInflux(final Position position) {
        final String databaseName = getInfluxDBName(position);

        try {
            WriteApi writeApi = getWriteAPIForDevice(databaseName);
            Instant deviceTime = Instant.ofEpochMilli(position.getDeviceTime().getTime());
            GeoLocation geoLocation = getGeoLocation(position, deviceTime);
            writeApi.writeMeasurement(WritePrecision.MS, geoLocation);

            getCalibratedFuelLevel(position, deviceTime).ifPresent(c -> writeApi.writeMeasurement(WritePrecision.MS, c));
            getExternalBatteryVoltage(position, deviceTime).ifPresent(e -> writeApi.writeMeasurement(WritePrecision.MS, e));
            getInternalBatteryVoltage(position, deviceTime).ifPresent(i -> writeApi.writeMeasurement(WritePrecision.MS, i));
        } catch (final NotFoundException e) {
            Log.debug(String.format("Influx db not found for %s. Please make sure it exists. Skipping current data point.", databaseName));
        }
    }

    private String getInfluxDBName(final Position position) {
        final Device device = Context.getDeviceManager().getById(position.getDeviceId());
        return String.format("%s",  device.getUniqueId());
    }

    private WriteApi getWriteAPIForDevice(final String databaseName) {
        if (!CLIENTS_MAP.containsKey(databaseName)) {
                final InfluxDBClient influxDBClientInstance = InfluxDBClientFactory.createV1(INFLUX_HOST_URL,
                                                                                             INFLUX_USER,
                                                                                             INFLUX_PASSWORD.toCharArray(),
                                                                                             databaseName,
                                                                                             RETENTION_POLICY);
                CLIENTS_MAP.put(databaseName, influxDBClientInstance);
        }
        return CLIENTS_MAP.get(databaseName).getWriteApi();
    }

    private Optional<Object> getValue(final Position position, final String propertyName) {
        if (position.getAttributes() != null && position.getAttributes().containsKey(propertyName)) {
            return Optional.of(position.getAttributes().get(propertyName));
        }
        return Optional.empty();
    }

    private Optional<CalibratedFuelLevel> getCalibratedFuelLevel(final Position position, final Instant deviceTime) {
        return getValue(position, Position.KEY_CALIBRATED_FUEL_LEVEL)
                .map(level -> {
                    CalibratedFuelLevel calibratedFuelLevel = new CalibratedFuelLevel();
                    calibratedFuelLevel.deviceTime = deviceTime;
                    calibratedFuelLevel.milliLiters = (Double) level * 1000.0;
                    return calibratedFuelLevel;
                });
    }

    private Optional<InternalBatteryVoltage> getInternalBatteryVoltage(final Position position, final Instant deviceTime) {
        return getValue(position, Position.KEY_BATTERY)
                .map(level -> {
                    InternalBatteryVoltage internalBatteryVoltage = new InternalBatteryVoltage();
                    internalBatteryVoltage.deviceTime = deviceTime;
                    internalBatteryVoltage.milliVolts = (Integer) level * 1.0;
                    return internalBatteryVoltage;
                });
    }

    private Optional<ExternalBatteryVoltage> getExternalBatteryVoltage(final Position position, final Instant deviceTime) {
        return getValue(position, Position.KEY_POWER)
                .map(level -> {
                    ExternalBatteryVoltage externalBatteryVoltage = new ExternalBatteryVoltage();
                    externalBatteryVoltage.deviceTime = deviceTime;
                    externalBatteryVoltage.milliVolts = (Integer) level * 1.0;
                    return externalBatteryVoltage;
                });
    }

    private GeoLocation getGeoLocation(final Position position, final Instant deviceTime) {
        GeoLocation geoLocation = new GeoLocation();
        geoLocation.deviceTime = deviceTime;
        geoLocation.latitude = position.getLatitude();
        geoLocation.longitude = position.getLongitude();
        geoLocation.distanceMeters = position.getDouble(Position.KEY_DISTANCE);
        geoLocation.totalDistanceMeters = position.getDouble(Position.KEY_TOTAL_DISTANCE);
        return geoLocation;
    }
}
