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
package org.openhab.binding.lutron.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_SWITCH;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

/**
 * Tests for {@link SwitchHandler}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class SwitchHandlerTest {
    private final ChildHandlerHarness harness = new ChildHandlerHarness("switch", Map.of("integrationId", 3));
    private final SwitchHandler handler = new SwitchHandler(harness.thing);

    @BeforeEach
    public void setUp() {
        handler.setCallback(harness.callback);
        handler.initialize();
    }

    @Test
    public void initializeQueriesTheState() {
        assertEquals(List.of("?OUTPUT,3,1"), harness.takeSent());
        assertEquals(ThingStatus.UNKNOWN, harness.status().getStatus());
    }

    @Test
    public void onAndOffAreFullAndZeroLevel() {
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
        assertEquals(List.of("#OUTPUT,3,1,100", "#OUTPUT,3,1,0"), harness.takeSent());
    }

    @Test
    public void anyNonZeroLevelIsOn() {
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "100.00");
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "0.00");
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "0.01");
        verify(harness.callback, times(2)).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
    }

    @Test
    public void commandsOnOtherChannelsAreIgnored() {
        harness.takeSent();
        handler.handleCommand(harness.channel("lightlevel"), OnOffType.ON);
        assertTrue(harness.sent.isEmpty());
    }

    @Test
    public void missingIntegrationIdIsAConfigurationError() {
        ChildHandlerHarness unconfigured = new ChildHandlerHarness("switch", Map.of());
        SwitchHandler unconfiguredHandler = new SwitchHandler(unconfigured.thing);
        unconfiguredHandler.setCallback(unconfigured.callback);
        unconfiguredHandler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, unconfigured.status().getStatusDetail());
        assertTrue(unconfigured.sent.isEmpty());
    }
}
