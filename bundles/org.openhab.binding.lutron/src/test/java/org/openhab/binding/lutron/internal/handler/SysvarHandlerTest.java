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
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_VARSTATE;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;

/**
 * Tests for {@link SysvarHandler}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class SysvarHandlerTest {
    private final ChildHandlerHarness harness = new ChildHandlerHarness("sysvar", Map.of("integrationId", 8));
    private final SysvarHandler handler = new SysvarHandler(harness.thing);

    @BeforeEach
    public void setUp() {
        handler.setCallback(harness.callback);
        handler.initialize();
    }

    @Test
    public void initializeAndLinkQueryTheVariable() {
        assertEquals(List.of("?SYSVAR,8,1"), harness.takeSent());
        handler.channelLinked(harness.channel(CHANNEL_VARSTATE));
        assertEquals(List.of("?SYSVAR,8,1"), harness.takeSent());
    }

    @Test
    public void numberCommandSetsTheVariable() {
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_VARSTATE), new DecimalType(2));
        handler.handleCommand(harness.channel(CHANNEL_VARSTATE), OnOffType.ON);
        assertEquals(List.of("#SYSVAR,8,1,2"), harness.takeSent());
    }

    @Test
    public void updateSetsTheState() {
        handler.handleUpdate(LutronCommandType.SYSVAR, "1", "3");
        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_VARSTATE), new DecimalType(3));
    }

    @Test
    public void unrelatedUpdatesAreIgnored() {
        clearInvocations(harness.callback);
        handler.handleUpdate(LutronCommandType.SYSVAR, "2", "3");
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "3");
        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void missingIntegrationIdIsAConfigurationError() {
        ChildHandlerHarness unconfigured = new ChildHandlerHarness("sysvar", Map.of());
        SysvarHandler unconfiguredHandler = new SysvarHandler(unconfigured.thing);
        unconfiguredHandler.setCallback(unconfigured.callback);
        unconfiguredHandler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, unconfigured.status().getStatusDetail());
        assertThrows(IllegalStateException.class, () -> new SysvarHandler(unconfigured.thing).getIntegrationId());
    }
}
