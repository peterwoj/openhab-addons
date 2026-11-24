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
import static org.mockito.Mockito.*;

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
 * Tests for {@link DeviceCommand}, in both its LIP and LEAP forms.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class DeviceCommandTest {
    private static final int ID = 10;

    private final LeapBridgeHandler leapBridge = mock(LeapBridgeHandler.class);

    private static DeviceCommand execute(TargetType type, int component, @Nullable Integer leapComponent, int action,
            @Nullable Object parameter) {
        return new DeviceCommand(type, LutronOperation.EXECUTE, ID, component, leapComponent, action, parameter);
    }

    private static JsonObject parse(@Nullable LeapCommand command) {
        assertNotNull(command);
        return JsonParser.parseString(command.toString()).getAsJsonObject();
    }

    private static String url(JsonObject request) {
        return request.getAsJsonObject("Header").get("Url").getAsString();
    }

    private static String commandType(JsonObject request) {
        return request.getAsJsonObject("Body").getAsJsonObject("Command").get("CommandType").getAsString();
    }

    @Test
    public void buttonPress() {
        assertEquals("#DEVICE,10,3,3",
                execute(TargetType.KEYPAD, 3, null, DeviceCommand.ACTION_PRESS, null).lipCommand());
    }

    @Test
    public void ledStateCarriesItsParameter() {
        assertEquals("#DEVICE,10,81,9,1",
                execute(TargetType.KEYPAD, 81, null, DeviceCommand.ACTION_LED_STATE, DeviceCommand.LED_ON)
                        .lipCommand());
    }

    @Test
    public void query() {
        DeviceCommand query = new DeviceCommand(TargetType.KEYPAD, LutronOperation.QUERY, ID, 81, null,
                DeviceCommand.ACTION_LED_STATE, null);
        assertEquals("?DEVICE,10,81,9", query.lipCommand());
    }

    @Test
    public void leapKeypadPressAndReleaseUseTheMappedButton() {
        when(leapBridge.getButton(ID, 2)).thenReturn(105);

        JsonObject press = parse(
                execute(TargetType.KEYPAD, 3, 2, DeviceCommand.ACTION_PRESS, null).leapCommand(leapBridge, null));
        assertEquals("/button/105/commandprocessor", url(press));
        assertEquals("PressAndHold", commandType(press));

        JsonObject release = parse(
                execute(TargetType.KEYPAD, 3, 2, DeviceCommand.ACTION_RELEASE, null).leapCommand(leapBridge, null));
        assertEquals("/button/105/commandprocessor", url(release));
        assertEquals("Release", commandType(release));
    }

    @Test
    public void leapKeypadWithUnknownButtonSendsNothing() {
        when(leapBridge.getButton(ID, 2)).thenReturn(0);
        assertNull(execute(TargetType.KEYPAD, 3, 2, DeviceCommand.ACTION_PRESS, null).leapCommand(leapBridge, null));
    }

    @Test
    public void leapKeypadWithoutLeapComponentSendsNothing() {
        assertNull(execute(TargetType.KEYPAD, 3, null, DeviceCommand.ACTION_PRESS, null).leapCommand(leapBridge, null));
        verifyNoInteractions(leapBridge);
    }

    @Test
    public void leapKeypadIgnoresHoldAndLed() {
        assertNull(execute(TargetType.KEYPAD, 3, 2, DeviceCommand.ACTION_HOLD, null).leapCommand(leapBridge, null));
        assertNull(execute(TargetType.KEYPAD, 81, 2, DeviceCommand.ACTION_LED_STATE, 1).leapCommand(leapBridge, null));
    }

    @Test
    public void leapVirtualKeypadPressIsPressAndRelease() {
        JsonObject press = parse(execute(TargetType.VIRTUALKEYPAD, 7, null, DeviceCommand.ACTION_PRESS, null)
                .leapCommand(leapBridge, null));
        assertEquals("/virtualbutton/7/commandprocessor", url(press));
        assertEquals("PressAndRelease", commandType(press));
    }

    @Test
    public void leapVirtualKeypadReleaseIsDropped() {
        assertNull(execute(TargetType.VIRTUALKEYPAD, 7, null, DeviceCommand.ACTION_RELEASE, null)
                .leapCommand(leapBridge, null));
    }

    @Test
    public void leapIgnoresOtherTargets() {
        assertNull(execute(TargetType.DIMMER, 1, 1, DeviceCommand.ACTION_PRESS, null).leapCommand(leapBridge, null));
    }
}
