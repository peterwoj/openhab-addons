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
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_SHADELEVEL;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

/**
 * Tests for {@link ShadeHandler} behind a LIP bridge and behind a LEAP bridge.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class ShadeHandlerTest {

    private @NonNullByDefault({}) ChildHandlerHarness harness;
    private @NonNullByDefault({}) ShadeHandler handler;

    private void start(Class<? extends LutronBridgeHandler> bridgeType) {
        harness = new ChildHandlerHarness("shade", Map.of("integrationId", 7), bridgeType);
        handler = new ShadeHandler(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();
        harness.takeSent();
        clearInvocations(harness.callback);
    }

    private void command(Command command) {
        handler.handleCommand(harness.channel(CHANNEL_SHADELEVEL), command);
    }

    @Test
    public void lipCommands() {
        start(IPBridgeHandler.class);

        command(new PercentType(40));
        command(UpDownType.UP);
        command(UpDownType.DOWN);
        command(StopMoveType.STOP);
        command(RefreshType.REFRESH);

        assertEquals(List.of("#OUTPUT,7,1,40", "#OUTPUT,7,2", "#OUTPUT,7,3", "#OUTPUT,7,4", "?OUTPUT,7,1"),
                harness.takeSent());
        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void lipBridgeReportsThePositionItself() {
        start(IPBridgeHandler.class);

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "35.00");
        handler.handleUpdate(LutronCommandType.OUTPUT, "32", "2", "60.00");

        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SHADELEVEL), new PercentType(35));
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SHADELEVEL), new PercentType(60));
    }

    @Test
    public void incompleteOrUnknownPositionUpdatesAreIgnored() {
        start(IPBridgeHandler.class);

        handler.handleUpdate(LutronCommandType.OUTPUT, "32", "2");
        handler.handleUpdate(LutronCommandType.OUTPUT, "32", "3", "60.00");
        handler.handleUpdate(LutronCommandType.OUTPUT, "1");
        handler.handleUpdate(LutronCommandType.OUTPUT, "2", "10");

        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void leapBridgeSetsTheTargetPositionOptimistically() {
        start(LeapBridgeHandler.class);

        command(new PercentType(40));
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SHADELEVEL), new PercentType(40));
        command(UpDownType.UP);
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SHADELEVEL), new PercentType(100));
        command(UpDownType.DOWN);
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SHADELEVEL), new PercentType(0));

        clearInvocations(harness.callback);
        command(StopMoveType.STOP);
        verify(harness.callback, never()).stateUpdated(any(), any());

        assertEquals(List.of("#OUTPUT,7,1,40", "#OUTPUT,7,2", "#OUTPUT,7,3", "#OUTPUT,7,4"), harness.takeSent());
    }

    @Test
    public void missingIntegrationIdIsAConfigurationError() {
        harness = new ChildHandlerHarness("shade", Map.of());
        handler = new ShadeHandler(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(harness.sent.isEmpty());
    }
}
