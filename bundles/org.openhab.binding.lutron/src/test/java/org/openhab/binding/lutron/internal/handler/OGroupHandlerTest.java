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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_GROUPSTATE;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.RefreshType;

/**
 * Tests for {@link OGroupHandler}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class OGroupHandlerTest {
    private final ChildHandlerHarness harness = new ChildHandlerHarness("ogroup", Map.of("integrationId", 6));
    private final OGroupHandler handler = new OGroupHandler(harness.thing);

    @BeforeEach
    public void setUp() {
        handler.setCallback(harness.callback);
        handler.initialize();
    }

    private void verifyState(String state) {
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_GROUPSTATE), new StringType(state));
    }

    @Test
    public void initializeAndRefreshQueryTheGroup() {
        assertEquals(List.of("?GROUP,6,3"), harness.takeSent());
        handler.handleCommand(harness.channel(CHANNEL_GROUPSTATE), RefreshType.REFRESH);
        handler.channelLinked(harness.channel(CHANNEL_GROUPSTATE));
        assertEquals(List.of("?GROUP,6,3", "?GROUP,6,3"), harness.takeSent());
    }

    @Test
    public void occupancyStates() {
        handler.handleUpdate(LutronCommandType.GROUP, "3", "3");
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        verifyState("OCCUPIED");
        handler.handleUpdate(LutronCommandType.GROUP, "3", "4");
        verifyState("UNOCCUPIED");
        handler.handleUpdate(LutronCommandType.GROUP, "3", "255");
        verifyState("UNKNOWN");
    }

    @Test
    public void unexpectedStateNumberIsUnknown() {
        handler.handleUpdate(LutronCommandType.GROUP, "3", "9");
        verifyState("UNKNOWN");
    }

    @Test
    public void unparsableOrUnrelatedUpdatesAreIgnored() {
        clearInvocations(harness.callback);
        assertDoesNotThrow(() -> handler.handleUpdate(LutronCommandType.GROUP, "3", "busy"));
        handler.handleUpdate(LutronCommandType.GROUP, "1", "3");
        handler.handleUpdate(LutronCommandType.GROUP, "3");
        handler.handleUpdate(LutronCommandType.OUTPUT, "3", "3");

        verify(harness.callback, never()).stateUpdated(any(), any());
        assertEquals(ThingStatus.UNKNOWN, harness.status().getStatus());
    }

    @Test
    public void invalidIntegrationIdIsAConfigurationError() {
        ChildHandlerHarness unconfigured = new ChildHandlerHarness("ogroup", Map.of("integrationId", 0));
        OGroupHandler unconfiguredHandler = new OGroupHandler(unconfigured.thing);
        unconfiguredHandler.setCallback(unconfigured.callback);
        unconfiguredHandler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, unconfigured.status().getStatusDetail());
        assertTrue(unconfigured.sent.isEmpty());
    }
}
