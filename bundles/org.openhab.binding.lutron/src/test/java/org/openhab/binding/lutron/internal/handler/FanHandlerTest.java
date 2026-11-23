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
import static org.mockito.Mockito.verify;
import static org.openhab.binding.lutron.internal.LutronBindingConstants.*;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ThingStatusDetail;

/**
 * Tests for {@link FanHandler}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class FanHandlerTest {
    private final ChildHandlerHarness harness = new ChildHandlerHarness("fan", Map.of("integrationId", 9));
    private final FanHandler handler = new FanHandler(harness.thing);

    @BeforeEach
    public void setUp() {
        handler.setCallback(harness.callback);
        handler.initialize();
    }

    @Test
    public void initializeQueriesTheSpeed() {
        assertEquals(List.of("?OUTPUT,9,1"), harness.takeSent());
    }

    @Test
    public void levelCommandsSnapToTheNearestFanSpeed() {
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_FANLEVEL), new PercentType(60));
        handler.handleCommand(harness.channel(CHANNEL_FANLEVEL), new PercentType(10));
        handler.handleCommand(harness.channel(CHANNEL_FANLEVEL), OnOffType.ON);
        handler.handleCommand(harness.channel(CHANNEL_FANLEVEL), OnOffType.OFF);
        assertEquals(List.of("#OUTPUT,9,1,75", "#OUTPUT,9,1,25", "#OUTPUT,9,1,100", "#OUTPUT,9,1,0"),
                harness.takeSent());
    }

    @Test
    public void speedCommandsUseTheNamedSpeed() {
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_FANSPEED), new StringType("Medium"));
        handler.handleCommand(harness.channel(CHANNEL_FANSPEED), new StringType("high"));
        assertEquals(List.of("#OUTPUT,9,1,50", "#OUTPUT,9,1,100"), harness.takeSent());
    }

    @Test
    public void levelUpdateSetsBothChannels() {
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "50.00");
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_FANLEVEL), new PercentType(50));
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_FANSPEED), new StringType("Medium"));
    }

    @Test
    public void missingIntegrationIdIsAConfigurationError() {
        ChildHandlerHarness unconfigured = new ChildHandlerHarness("fan", Map.of());
        FanHandler unconfiguredHandler = new FanHandler(unconfigured.thing);
        unconfiguredHandler.setCallback(unconfigured.callback);
        unconfiguredHandler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, unconfigured.status().getStatusDetail());
        assertThrows(IllegalStateException.class, unconfiguredHandler::getIntegrationId);
    }
}
