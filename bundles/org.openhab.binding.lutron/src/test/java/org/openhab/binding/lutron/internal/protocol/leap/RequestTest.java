/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.lutron.internal.protocol.leap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.lutron.internal.protocol.FanSpeedType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests that every {@link Request} builder produces a well-formed LEAP request.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class RequestTest {

    private static JsonObject parse(String request) {
        assertFalse(request.contains("\n"), "LEAP requests are newline-delimited, so they must be a single line");
        return JsonParser.parseString(request).getAsJsonObject();
    }

    private static String url(JsonObject request) {
        return request.getAsJsonObject("Header").get("Url").getAsString();
    }

    private static JsonObject command(JsonObject request) {
        return request.getAsJsonObject("Body").getAsJsonObject("Command");
    }

    static Stream<Arguments> readAndSubscribeRequests() {
        return Stream.of( //
                Arguments.of(Request.ping(), "ReadRequest", "/server/1/status/ping"),
                Arguments.of(Request.getDevices(), "ReadRequest", "/device"),
                Arguments.of(Request.getDevices(true), "ReadRequest", "/device?where=IsThisDevice:true"),
                Arguments.of(Request.getDevices(false), "ReadRequest", "/device?where=IsThisDevice:false"),
                Arguments.of(Request.getVirtualButtons(), "ReadRequest", "/virtualbutton"),
                Arguments.of(Request.getButtonGroups(), "ReadRequest", "/buttongroup"),
                Arguments.of(Request.getProject(), "ReadRequest", "/project"),
                Arguments.of(Request.getAreas(), "ReadRequest", "/area"),
                Arguments.of(Request.getOccupancyGroups(), "ReadRequest", "/occupancygroup"),
                Arguments.of(Request.getZoneStatus(14), "ReadRequest", "/zone/14/status"),
                Arguments.of(Request.getOccupancyGroupStatus(), "ReadRequest", "/occupancygroup/status"),
                Arguments.of(Request.getZoneStatuses(), "ReadRequest",
                        "/zone/status/expanded?where=Zone.ControlType:\"Dimmed\"|\"Switched\"|\"CCO\"|\"Shade\""),
                Arguments.of(Request.subscribeButtonStatus(101), "SubscribeRequest", "/button/101/status/event"),
                Arguments.of(Request.subscribeOccupancyGroupStatus(), "SubscribeRequest", "/occupancygroup/status"),
                Arguments.of(Request.subscribeZoneStatus(), "SubscribeRequest", "/zone/status"),
                Arguments.of(Request.subscribeAreaStatus(), "SubscribeRequest", "/area/status"));
    }

    @ParameterizedTest
    @MethodSource("readAndSubscribeRequests")
    public void readAndSubscribeRequestsHaveTypeAndUrl(String request, String communiqueType, String url) {
        JsonObject json = parse(request);
        assertEquals(communiqueType, json.get("CommuniqueType").getAsString());
        assertEquals(url, url(json));
        assertFalse(json.has("Body"), "read and subscribe requests carry no body");
    }

    @Test
    public void goToLevel() {
        JsonObject json = parse(Request.goToLevel(3, 75));
        assertEquals("CreateRequest", json.get("CommuniqueType").getAsString());
        assertEquals("/zone/3/commandprocessor", url(json));
        JsonObject command = command(json);
        assertEquals("GoToLevel", command.get("CommandType").getAsString());
        JsonArray parameters = command.getAsJsonArray("Parameter");
        assertEquals(1, parameters.size());
        assertEquals("Level", parameters.get(0).getAsJsonObject().get("Type").getAsString());
        assertEquals(75, parameters.get(0).getAsJsonObject().get("Value").getAsInt());
    }

    @Test
    public void goToDimmedLevelWithFadeOnly() {
        JsonObject parameters = command(parse(Request.goToDimmedLevel(3, 20, "00:00:04")))
                .getAsJsonObject("DimmedLevelParameters");
        assertEquals(20, parameters.get("Level").getAsInt());
        assertEquals("00:00:04", parameters.get("FadeTime").getAsString());
        assertFalse(parameters.has("DelayTime"));
    }

    @Test
    public void goToDimmedLevelWithFadeAndDelay() {
        JsonObject command = command(parse(Request.goToDimmedLevel(3, 20, "00:00:04", "00:01:00")));
        assertEquals("GoToDimmedLevel", command.get("CommandType").getAsString());
        JsonObject parameters = command.getAsJsonObject("DimmedLevelParameters");
        assertEquals("00:01:00", parameters.get("DelayTime").getAsString());
    }

    @Test
    public void goToFanSpeedUsesTheLeapName() {
        JsonObject command = command(parse(Request.goToFanSpeed(8, FanSpeedType.MEDIUMHIGH)));
        assertEquals("GoToFanSpeed", command.get("CommandType").getAsString());
        assertEquals("MediumHigh", command.getAsJsonObject("FanSpeedParameters").get("FanSpeed").getAsString());
    }

    @Test
    public void buttonVirtualButtonAndZoneCommands() {
        JsonObject button = parse(Request.buttonCommand(101, CommandType.PRESSANDHOLD));
        assertEquals("/button/101/commandprocessor", url(button));
        assertEquals("PressAndHold", command(button).get("CommandType").getAsString());

        JsonObject virtualButton = parse(Request.virtualButtonCommand(5, CommandType.PRESSANDRELEASE));
        assertEquals("/virtualbutton/5/commandprocessor", url(virtualButton));
        assertEquals("PressAndRelease", command(virtualButton).get("CommandType").getAsString());

        JsonObject zone = parse(Request.zoneCommand(9, CommandType.STOP));
        assertEquals("/zone/9/commandprocessor", url(zone));
        assertEquals("Stop", command(zone).get("CommandType").getAsString());
    }

    @Test
    public void urlWithQuotesIsEscaped() {
        // The expanded zone status query contains quotes, which must survive JSON encoding
        String request = Request.getZoneStatuses();
        assertTrue(request.contains("\\\"Dimmed\\\""), request);
    }
}
