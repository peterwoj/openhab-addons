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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.handler.LeapBridgeHandler;
import org.openhab.binding.lutron.internal.protocol.leap.LeapCommand;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.binding.lutron.internal.protocol.lip.LutronOperation;
import org.openhab.binding.lutron.internal.protocol.lip.Monitoring;
import org.openhab.binding.lutron.internal.protocol.lip.TargetType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the LIP command classes without a LEAP equivalent: {@link LIPCommand}, {@link SysvarCommand},
 * {@link TimeclockCommand}, {@link ModeCommand} and {@link GroupCommand}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class LipCommandsTest {
    private final LeapBridgeHandler leapBridge = mock(LeapBridgeHandler.class);

    @Test
    public void bridgeCommandWithoutIntegrationId() {
        LIPCommand command = new LIPCommand(TargetType.BRIDGE, LutronOperation.EXECUTE, LutronCommandType.MONITORING,
                null, Monitoring.PROMPT, Monitoring.ACTION_DISABLE);
        assertEquals("#MONITORING,12,2", command.lipCommand());
        assertEquals("#MONITORING,12,2", command.toString());
        assertNull(command.leapCommand(leapBridge, 1));
    }

    @Test
    public void lipCommandWithoutParameters() {
        assertEquals("?SYSTEM,10",
                new LIPCommand(TargetType.BRIDGE, LutronOperation.QUERY, LutronCommandType.SYSTEM, null, 10)
                        .lipCommand());
        assertEquals("?OUTPUT,4",
                new LIPCommand(TargetType.DIMMER, LutronOperation.QUERY, LutronCommandType.OUTPUT, 4).lipCommand());
    }

    @Test
    public void numberParameterAccess() {
        LIPCommand command = new LIPCommand(TargetType.BRIDGE, LutronOperation.EXECUTE, LutronCommandType.MONITORING,
                null, 5, "text");
        assertEquals(5, command.getNumberParameter(0));
        assertThrows(IllegalArgumentException.class, () -> command.getNumberParameter(1));
        assertThrows(IllegalArgumentException.class, () -> command.getNumberParameter(2));
    }

    @Test
    public void sysvar() {
        assertEquals("#SYSVAR,2,1,5",
                new SysvarCommand(LutronOperation.EXECUTE, 2, SysvarCommand.ACTION_GETSETSYSVAR, 5).lipCommand());
        SysvarCommand query = new SysvarCommand(LutronOperation.QUERY, 2, SysvarCommand.ACTION_GETSETSYSVAR, null);
        assertEquals("?SYSVAR,2,1", query.lipCommand());
        assertNull(query.leapCommand(leapBridge, 1));
    }

    @Test
    public void timeclockEventEnableAndDisable() {
        assertEquals("#TIMECLOCK,3,6,4,1",
                new TimeclockCommand(LutronOperation.EXECUTE, 3, TimeclockCommand.ACTION_SETEVENT, 4, true)
                        .lipCommand());
        assertEquals("#TIMECLOCK,3,6,4,2",
                new TimeclockCommand(LutronOperation.EXECUTE, 3, TimeclockCommand.ACTION_SETEVENT, 4, false)
                        .lipCommand());
    }

    @Test
    public void timeclockQueries() {
        TimeclockCommand sunrise = new TimeclockCommand(LutronOperation.QUERY, 3, TimeclockCommand.ACTION_SUNRISE, null,
                null);
        assertEquals("?TIMECLOCK,3,2", sunrise.lipCommand());
        assertNull(sunrise.leapCommand(leapBridge, 1));
        assertEquals("#TIMECLOCK,3,5,7",
                new TimeclockCommand(LutronOperation.EXECUTE, 3, TimeclockCommand.ACTION_EXECEVENT, 7, null)
                        .lipCommand());
    }

    @Test
    public void greenMode() {
        assertEquals("#MODE,4,1,2",
                new ModeCommand(LutronOperation.EXECUTE, 4, ModeCommand.ACTION_STEP, 2).lipCommand());
        ModeCommand query = new ModeCommand(LutronOperation.QUERY, 4, ModeCommand.ACTION_STEP, null);
        assertEquals("?MODE,4,1", query.lipCommand());
        assertNull(query.leapCommand(leapBridge, 1));
    }

    @Test
    public void occupancyGroup() {
        GroupCommand query = new GroupCommand(LutronOperation.QUERY, 6, GroupCommand.ACTION_GROUPSTATE, null);
        assertEquals("?GROUP,6,3", query.lipCommand());
        assertEquals("~GROUP,6,3,3", new GroupCommand(LutronOperation.RESPONSE, 6, GroupCommand.ACTION_GROUPSTATE,
                GroupCommand.STATE_GRP_OCCUPIED).lipCommand());
    }

    @Test
    public void leapOccupancyQueryReadsAllGroups() {
        LeapCommand leap = new GroupCommand(LutronOperation.QUERY, 6, GroupCommand.ACTION_GROUPSTATE, null)
                .leapCommand(leapBridge, null);
        assertNotNull(leap);
        JsonObject request = JsonParser.parseString(leap.toString()).getAsJsonObject();
        assertEquals("/occupancygroup/status", request.getAsJsonObject("Header").get("Url").getAsString());
        assertNull(new GroupCommand(LutronOperation.QUERY, 6, 1, null).leapCommand(leapBridge, null));
    }
}
