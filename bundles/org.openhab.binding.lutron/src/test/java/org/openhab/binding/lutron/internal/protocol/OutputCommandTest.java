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
package org.openhab.binding.lutron.internal.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.handler.LeapBridgeHandler;
import org.openhab.binding.lutron.internal.protocol.leap.LeapCommand;
import org.openhab.binding.lutron.internal.protocol.lip.LutronOperation;
import org.openhab.binding.lutron.internal.protocol.lip.TargetType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link OutputCommand}, in both its LIP and LEAP forms.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class OutputCommandTest {
    private static final int ID = 5;
    private static final int ZONE = 12;

    private final LeapBridgeHandler leapBridge = mock(LeapBridgeHandler.class);

    private static OutputCommand execute(TargetType type, int action, @Nullable Number parameter,
            @Nullable LutronDuration fade, @Nullable LutronDuration delay) {
        return new OutputCommand(type, LutronOperation.EXECUTE, ID, action, parameter, fade, delay);
    }

    private static OutputCommand query(TargetType type, int action) {
        return new OutputCommand(type, LutronOperation.QUERY, ID, action, (Integer) null, null, null);
    }

    private JsonObject leap(OutputCommand command) {
        LeapCommand leapCommand = command.leapCommand(leapBridge, ZONE);
        assertNotNull(leapCommand);
        return JsonParser.parseString(leapCommand.toString()).getAsJsonObject();
    }

    private static String url(JsonObject request) {
        return request.getAsJsonObject("Header").get("Url").getAsString();
    }

    private static JsonObject command(JsonObject request) {
        return request.getAsJsonObject("Body").getAsJsonObject("Command");
    }

    @Test
    public void dimmerLevelWithFade() {
        assertEquals("#OUTPUT,5,1,50,2",
                execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 50, new LutronDuration(2), null)
                        .lipCommand());
    }

    @Test
    public void delayWithoutFadeInsertsZeroFadePlaceholder() {
        assertEquals("#OUTPUT,5,1,50,0,3",
                execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 50, null, new LutronDuration(3))
                        .lipCommand());
    }

    @Test
    public void fadeAndDelayUseLipDurationFormat() {
        assertEquals("#OUTPUT,5,1,100,0.25,1:40", execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 100,
                new LutronDuration("0.25"), new LutronDuration(100)).lipCommand());
    }

    @Test
    public void decimalLevelKeepsItsScale() {
        assertEquals("#OUTPUT,5,1,33.50",
                execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, new BigDecimal("33.50"), null, null)
                        .lipCommand());
    }

    @Test
    public void actionWithoutParameter() {
        assertEquals("#OUTPUT,5,2",
                execute(TargetType.SHADE, OutputCommand.ACTION_STARTRAISING, null, null, null).lipCommand());
    }

    @Test
    public void queryHasNoParameters() {
        assertEquals("?OUTPUT,5,1", query(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL).lipCommand());
        assertEquals("?OUTPUT,5,1", query(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL).toString());
    }

    @Test
    public void ccoPulseTimeAlwaysUsesADecimalPoint() {
        Locale saved = Locale.getDefault();
        try {
            // A German default locale would format 1.5 as "1,50", which the processor reads as two parameters
            Locale.setDefault(Locale.GERMANY);
            assertEquals("#OUTPUT,5,6,1.50",
                    execute(TargetType.CCO, OutputCommand.ACTION_PULSE, 1.5, null, null).lipCommand());
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    public void ccoStateIsNotFormattedAsAPulseTime() {
        assertEquals("#OUTPUT,5,1,100",
                execute(TargetType.CCO, OutputCommand.ACTION_STATE, 100, null, null).lipCommand());
    }

    @Test
    public void fanSpeedIsSentAsItsPercentage() {
        OutputCommand command = new OutputCommand(TargetType.FAN, LutronOperation.EXECUTE, ID,
                OutputCommand.ACTION_ZONELEVEL, FanSpeedType.MEDIUMHIGH, null, null);
        assertEquals("#OUTPUT,5,1,75", command.lipCommand());
    }

    @Test
    public void leapNeedsAZone() {
        assertNull(execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 50, null, null).leapCommand(leapBridge,
                null));
    }

    @Test
    public void leapZoneLevelQueryReadsZoneStatus() {
        JsonObject request = leap(query(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL));
        assertEquals("ReadRequest", request.get("CommuniqueType").getAsString());
        assertEquals("/zone/12/status", url(request));
    }

    @Test
    public void leapIgnoresOtherQueries() {
        assertNull(query(TargetType.SHADE, OutputCommand.ACTION_STOP).leapCommand(leapBridge, ZONE));
    }

    @Test
    public void leapSwitchGoesToLevel() {
        JsonObject request = leap(execute(TargetType.SWITCH, OutputCommand.ACTION_ZONELEVEL, 100, null, null));
        assertEquals("/zone/12/commandprocessor", url(request));
        assertEquals("GoToLevel", command(request).get("CommandType").getAsString());
        assertEquals(100,
                command(request).getAsJsonArray("Parameter").get(0).getAsJsonObject().get("Value").getAsInt());
    }

    @Test
    public void leapDimmerWithoutTimesGoesToLevel() {
        JsonObject request = leap(execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 40, null, null));
        assertEquals("GoToLevel", command(request).get("CommandType").getAsString());
    }

    @Test
    public void leapDimmerWithFadeGoesToDimmedLevel() {
        JsonObject request = leap(
                execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 40, new LutronDuration("1.50"), null));
        JsonObject command = command(request);
        assertEquals("GoToDimmedLevel", command.get("CommandType").getAsString());
        JsonObject parameters = command.getAsJsonObject("DimmedLevelParameters");
        assertEquals(40, parameters.get("Level").getAsInt());
        assertEquals("00:00:02", parameters.get("FadeTime").getAsString());
        assertEquals("00:00:00", parameters.get("DelayTime").getAsString());
    }

    @Test
    public void leapDimmerWithDelayOnlyUsesZeroFade() {
        JsonObject parameters = command(
                leap(execute(TargetType.DIMMER, OutputCommand.ACTION_ZONELEVEL, 40, null, new LutronDuration(3600))))
                .getAsJsonObject("DimmedLevelParameters");
        assertEquals("00:00:00", parameters.get("FadeTime").getAsString());
        assertEquals("01:00:00", parameters.get("DelayTime").getAsString());
    }

    @Test
    public void leapDimmerIgnoresRaiseAndLower() {
        assertNull(execute(TargetType.DIMMER, OutputCommand.ACTION_STARTRAISING, null, null, null)
                .leapCommand(leapBridge, ZONE));
    }

    @Test
    public void leapFanGoesToNamedSpeed() {
        OutputCommand fan = new OutputCommand(TargetType.FAN, LutronOperation.EXECUTE, ID,
                OutputCommand.ACTION_ZONELEVEL, FanSpeedType.MEDIUM, null, null);
        JsonObject command = command(leap(fan));
        assertEquals("GoToFanSpeed", command.get("CommandType").getAsString());
        assertEquals("Medium", command.getAsJsonObject("FanSpeedParameters").get("FanSpeed").getAsString());
    }

    @Test
    public void leapFanFromPercentage() {
        JsonObject command = command(leap(execute(TargetType.FAN, OutputCommand.ACTION_ZONELEVEL, 20, null, null)));
        assertEquals("Low", command.getAsJsonObject("FanSpeedParameters").get("FanSpeed").getAsString());
    }

    @Test
    public void leapShadeMotionCommands() {
        assertEquals("Raise",
                command(leap(execute(TargetType.SHADE, OutputCommand.ACTION_STARTRAISING, null, null, null)))
                        .get("CommandType").getAsString());
        assertEquals("Lower",
                command(leap(execute(TargetType.SHADE, OutputCommand.ACTION_STARTLOWERING, null, null, null)))
                        .get("CommandType").getAsString());
        assertEquals("Stop", command(leap(execute(TargetType.SHADE, OutputCommand.ACTION_STOP, null, null, null)))
                .get("CommandType").getAsString());
        assertEquals("GoToLevel",
                command(leap(execute(TargetType.SHADE, OutputCommand.ACTION_ZONELEVEL, 30, null, null)))
                        .get("CommandType").getAsString());
    }

    @Test
    public void leapIgnoresUnsupportedTargets() {
        assertNull(execute(TargetType.BLIND, OutputCommand.ACTION_LIFTLEVEL, 50, null, null).leapCommand(leapBridge,
                ZONE));
        assertNull(execute(TargetType.CCO, OutputCommand.ACTION_PULSE, 1, null, null).leapCommand(leapBridge, ZONE));
    }

    @Test
    public void leapIgnoresLevelWithoutParameter() {
        assertNull(execute(TargetType.SWITCH, OutputCommand.ACTION_ZONELEVEL, null, null, null).leapCommand(leapBridge,
                ZONE));
    }

    @Test
    public void leapIgnoresResponseOperation() {
        OutputCommand response = new OutputCommand(TargetType.DIMMER, LutronOperation.RESPONSE, ID,
                OutputCommand.ACTION_ZONELEVEL, 10, null, null);
        assertNull(response.leapCommand(leapBridge, ZONE));
    }
}
