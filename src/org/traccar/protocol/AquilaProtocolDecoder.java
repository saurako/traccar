/*
 * Copyright 2015 - 2018 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Context;
import org.traccar.DeviceSession;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class AquilaProtocolDecoder extends BaseProtocolDecoder {

    public static final int EXTERNAL_BATTERY_DISCONNECT_EVENT_ID = 3;
    public static final int EXTERNAL_BATTERY_DISCONNECT_EVENT_ID_STORED = 103;

    public AquilaProtocolDecoder(AquilaProtocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN_A = new PatternBuilder()
            .text("$$")
            .expression("[^,]*,")                // client
            .number("(d+),")                     // device serial number
            .number("(d+),")                     // event
            .number("(-?d+.d+),")                // latitude
            .number("(-?d+.d+),")                // longitude
            .number("(dd)(dd)(dd)")              // date (yymmdd)
            .number("(dd)(dd)(dd),")             // time (hhmmss)
            .expression("([AV]),")               // validity
            .groupBegin()
            .number("(d+),")                     // gsm
            .number("(d+.?d*),")                     // speed
            .number("(d+),")                     // distance
            .groupBegin()
            .number("d+,")                       // driver code
            .number("(d+),")                     // fuel
            .number("([01]),")                   // io 1
            .number("[01],")                     // case open switch
            .number("[01],")                     // over speed start
            .number("[01],")                     // over speed end
            .number("(?:d+,){3}")                // reserved
            .number("([01]),")                   // power status
            .number("([01]),")                   // io 2
            .number("d+,")                       // reserved
            .number("([01]),")                   // ignition
            .number("[01],")                     // ignition off event
            .number("(?:d+,){7}")                // reserved
            .number("[01],")                     // corner packet
            .number("(?:d+,){8}")                // reserved
            .number("([01]),")                   // course bit 0
            .number("([01]),")                   // course bit 1
            .number("([01]),")                   // course bit 2
            .number("([01]),")                   // course bit 3
            .or()
            .number("(d+),")                     // course
            .number("(?:d+,){3}")                // reserved
            .number("[01],")                     // over speed start
            .number("[01],")                     // over speed end
            .number("(?:d+,){3}")                // reserved
            .number("([01]),")                   // power status
            .number("(?:d+,){2}")                // reserved
            .number("[01],")                     // ignition on event
            .number("([01]),")                   // ignition
            .number("[01],")                     // ignition off event
            .number("(?:d+,){5}")                // reserved
            .number("[01],")                     // low battery
            .number("[01],")                     // corner packet
            .number("(?:d+,){6}")                // reserved
            .number("[01],")                     // hard acceleration
            .number("[01],")                     // hard braking
            .number("[01],[01],[01],[01],")      // course bits
            .number("(d+),")                     // external voltage
            .number("(d+),")                     // internal voltage
            .number("(?:d+,){6}")                // reserved
            .expression("P([^,]+),")             // obd
            .expression("D([^,]+),")             // dtcs
            .number("-?d+,")                     // accelerometer x
            .number("-?d+,")                     // accelerometer y
            .number("-?d+,")                     // accelerometer z
            .number("d+,")                       // delta distance
            .or()
            .number("(d+),")                     // course
            .number("(d+),")                     // satellites
            .number("(d+.d+),")                  // hdop
            .number("(?:d+,){2}")                // reserved
            .number("(d+),")                     // adc 1
            .number("([01]),")                   // di 1
            .number("([01]),")                     // case open
            .number("([01]),")                     // over speed start
            .number("([01]),")                     // over speed end
            .number("(?:[01],){2}")              // reserved
            .number("[01],")                     // immobilizer
            .number("([01]),")                   // power status
            .number("([01]),")                   // di 2
            .number("(?:[01],){2}")              // reserved
            .number("([01]),")                   // ignition
            .number("(?:[01],){6}")              // reserved
            .number("([01]),")                     // low battery
            .number("[01],")                     // corner packet
            .number("(?:[01],){4}")              // reserved
            .number("[01],")                     // do 1
            .number("[01],")                     // reserved
            .number("([01]),")                     // hard acceleration
            .number("([01]),")                     // hard braking
            .number("(?:[01],){4}")              // reserved
            .number("(d+),")                     // external voltage
            .number("(d+),")                     // internal voltage
            .groupEnd()
            .or()
            .number("(d+),")                     // sensor id
            .expression("([^,]+),")              // sensor data
            .groupEnd()
            .text("*")
            .number("xx")                        // checksum
            .compile();

    private Position decodeA(Channel channel, SocketAddress remoteAddress, String sentence) {

        Parser parser = new Parser(PATTERN_A, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        int event = parser.nextInt(0);
        position.set(Position.KEY_EVENT, event);
        if (event == EXTERNAL_BATTERY_DISCONNECT_EVENT_ID || event == EXTERNAL_BATTERY_DISCONNECT_EVENT_ID_STORED) {
            position.set(Position.KEY_EXTERNAL_BATTERY_DISCONNECT, true);
        }

        position.setLatitude(parser.nextDouble(0));
        position.setLongitude(parser.nextDouble(0));

        position.setTime(parser.nextDateTime(Parser.DateTimeFormat.YMD_HMS, "GMT"));

        position.setValid(parser.next().equals("A"));

        if (parser.hasNext(3)) {
            position.set(Position.KEY_RSSI, parser.nextInt(0));
            position.setSpeed(UnitsConverter.knotsFromKph(parser.nextDouble(0)));
            position.set(Position.KEY_ODOMETER, parser.nextInt(0));
        }

        if (parser.hasNext(9)) {

            position.set(Position.KEY_FUEL_LEVEL, parser.nextInt());
            position.set(Position.PREFIX_IN + 1, parser.next());
            position.set(Position.KEY_CHARGE, parser.next().equals("1"));
            position.set(Position.PREFIX_IN + 2, parser.next());

            position.set(Position.KEY_IGNITION, parser.nextInt(0) == 1);

            int course = (parser.nextInt(0) << 3) + (parser.nextInt(0) << 2)
                    + (parser.nextInt(0) << 1) + parser.nextInt(0);
            if (course > 0 && course <= 8) {
                position.setCourse((course - 1) * 45);
            }

        } else if (parser.hasNext(7)) {

            position.setCourse(parser.nextInt(0));

            position.set(Position.KEY_CHARGE, parser.next().equals("1"));
            position.set(Position.KEY_IGNITION, parser.nextInt(0) == 1);
            position.set(Position.KEY_POWER, parser.nextInt(0));
            position.set(Position.KEY_BATTERY, parser.nextInt(0));

            String obd = parser.next();
            position.set("obd", obd.substring(1, obd.length() - 1));

            String dtcs = parser.next();
            position.set(Position.KEY_DTCS, dtcs.substring(1, dtcs.length() - 1).replace('|', ' '));

        } else if (parser.hasNext(16)) {

            position.setCourse(parser.nextInt(0));

            position.set(Position.KEY_SATELLITES, parser.nextInt(0));
            position.set(Position.KEY_HDOP, parser.nextDouble(0));
            position.set(Position.PREFIX_ADC + 1, parser.nextInt(0));
            position.set(Position.PREFIX_IN + 1, parser.nextInt(0));
            position.set(Position.KEY_CASE_OPEN, parser.nextInt(0) == 1); // case open
            position.set(Position.KEY_OVERSPEED_START, parser.nextInt(0) == 1); // over speed start
            position.set(Position.KEY_OVERSPEED_END, parser.nextInt(0) == 1); // over speed end
            position.set(Position.KEY_CHARGE, parser.next().equals("1")); // power status -> 1 = internal battery
            position.set(Position.PREFIX_IN + 2, parser.nextInt(0));
            position.set(Position.KEY_IGNITION, parser.nextInt(0) == 1);
            position.set(Position.KEY_INTERNAL_BATTERY_LOW, parser.nextInt(0) == 1); // 1 == internal battery low
            position.set(Position.KEY_HARD_ACCELERATION, parser.nextInt(0) == 1); // 1 =- hard acceleration detected
            position.set(Position.KEY_HARD_BRAKING, parser.nextInt(0) == 1); // 1 == hard braking detected
            position.set(Position.KEY_POWER, parser.nextInt(0));
            position.set(Position.KEY_BATTERY, parser.nextInt(0));

        } else if (parser.hasNext(2)) {

            position.set("sensorId", parser.nextInt());
            position.set("sensorData", parser.next());

        }

        return position;
    }

    private static final Pattern PATTERN_B = new PatternBuilder()
            .text("$Header,")
            .expression("[^,]+,")                // client
            .expression("[^,]+,")                // firmware version
            .expression(".{2},")                 // type
            .number("d+,")                       // message id
            .expression("[LH],")                 // status
            .number("(d+),")                     // imei
            .expression("[^,]+,")                // registration number
            .number("([01]),")                   // validity
            .number("(dd)(dd)(dddd),")           // date (ddmmyyyy)
            .number("(dd)(dd)(dd),")             // time (hhmmss)
            .number("(-?d+.d+),")                // latitude
            .expression("([NS]),")
            .number("(-?d+.d+),")                // longitude
            .expression("([EW]),")
            .number("(d+.d+),")                  // speed
            .number("(d+),")                     // course
            .number("(d+),")                     // satellites
            .number("(-?d+.d+),")                // altitude
            .number("(d+.d+),")                  // pdop
            .number("(d+.d+),")                  // hdop
            .expression("[^,]+,")                // operator
            .number("([01]),")                   // ignition
            .number("([01]),")                   // charge
            .number("(d+.d+),")                  // power
            .number("(d+.d+),")                  // battery
            .number("([01]),")                   // emergency
            .expression("[CO],")                 // tamper
            .number("(d+),")                     // rssi
            .number("(d+),")                     // mcc
            .number("(d+),")                     // mnc
            .number("(x+),")                     // lac
            .number("(x+),")                     // cid
            .number("(d+),(x+),(x+),")           // cell 1
            .number("(d+),(x+),(x+),")           // cell 2
            .number("(d+),(x+),(x+),")           // cell 3
            .number("(d+),(x+),(x+),")           // cell 4
            .number("([01])+,")                  // inputs
            .number("([01])+,")                  // outputs
            .number("d+,")                       // frame number
            .number("(d+.d+),")                  // adc1
            .number("(d+.d+),")                  // adc2
            .number("d+,")                       // delta distance
            .any()
            .compile();

    private Position decodeB(Channel channel, SocketAddress remoteAddress, String sentence) {

        Parser parser = new Parser(PATTERN_B, sentence);
        if (!parser.matches()) {
            return null;
        }

        String id = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        position.setValid(parser.nextInt() == 1);
        position.setTime(parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS, "GMT"));
        position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_HEM));
        position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_HEM));
        position.setSpeed(UnitsConverter.knotsFromKph(parser.nextDouble()));
        position.setCourse(parser.nextInt());

        position.set(Position.KEY_SATELLITES, parser.nextInt());

        position.setAltitude(parser.nextDouble());

        position.set(Position.KEY_PDOP, parser.nextDouble());
        position.set(Position.KEY_HDOP, parser.nextDouble());
        position.set(Position.KEY_IGNITION, parser.nextInt() == 1);
        position.set(Position.KEY_CHARGE, parser.nextInt() == 1);
        position.set(Position.KEY_POWER, parser.nextDouble());
        position.set(Position.KEY_BATTERY, parser.nextDouble());

        if (parser.nextInt() == 1) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
            if (channel != null) {
                String password = Context.getIdentityManager().lookupAttributeString(
                        position.getDeviceId(), getProtocolName() + ".language", "aquila123", true);
                channel.write("#set$" + id + "@" + password + "#EMR_MODE:0*");
            }
        }

        Network network = new Network();

        int rssi = parser.nextInt();
        int mcc = parser.nextInt();
        int mnc = parser.nextInt();

        network.addCellTower(CellTower.from(mcc, mnc, parser.nextHexInt(), parser.nextHexInt(), rssi));
        for (int i = 0; i < 4; i++) {
            rssi = parser.nextInt();
            network.addCellTower(CellTower.from(mcc, mnc, parser.nextHexInt(), parser.nextHexInt(), rssi));
        }

        position.setNetwork(network);

        position.set(Position.KEY_INPUT, parser.nextBinInt());
        position.set(Position.KEY_OUTPUT, parser.nextBinInt());
        position.set(Position.PREFIX_ADC + 1, parser.nextDouble());
        position.set(Position.PREFIX_ADC + 2, parser.nextDouble());

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;

        if (sentence.startsWith("$$")) {
            return decodeA(channel, remoteAddress, sentence);
        } else {
            return decodeB(channel, remoteAddress, sentence);
        }
    }

}
