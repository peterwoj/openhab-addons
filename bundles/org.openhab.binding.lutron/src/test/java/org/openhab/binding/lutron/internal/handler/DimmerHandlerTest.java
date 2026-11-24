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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_LIGHTLEVEL;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.LutronDuration;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.types.Command;

/**
 * Tests for {@link DimmerHandler}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class DimmerHandlerTest {

    private @NonNullByDefault({}) ChildHandlerHarness harness;
    private @NonNullByDefault({}) DimmerHandler handler;

    private DimmerHandler start(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("integrationId", 5);
        config.putAll(extra);
        harness = new ChildHandlerHarness("dimmer", config);
        handler = new DimmerHandler(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();
        return handler;
    }

    private DimmerHandler startOnline(Map<String, Object> extra) {
        start(extra);
        harness.takeSent();
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "0.00");
        return handler;
    }

    private void command(Command command) {
        handler.handleCommand(harness.channel(CHANNEL_LIGHTLEVEL), command);
    }

    @Test
    public void initializeQueriesTheLevelAndWaitsForTheAnswer() {
        start(Map.of());

        assertEquals(ThingStatus.UNKNOWN, harness.status().getStatus());
        assertEquals(List.of("?OUTPUT,5,1"), harness.takeSent());

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "42.00");

        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_LIGHTLEVEL), new PercentType(42));
    }

    @Test
    public void percentCommandUsesAQuarterSecondFade() {
        startOnline(Map.of());
        command(new PercentType(30));
        assertEquals(List.of("#OUTPUT,5,1,30,0.25"), harness.takeSent());
    }

    @Test
    public void onUsesTheConfiguredLevelAndFadeIn() {
        startOnline(Map.of("onLevel", 80, "fadeInTime", 2));
        command(OnOffType.ON);
        assertEquals(List.of("#OUTPUT,5,1,80,2"), harness.takeSent());
    }

    @Test
    public void offUsesTheConfiguredFadeOut() {
        startOnline(Map.of("fadeOutTime", new BigDecimal("3.5")));
        command(OnOffType.OFF);
        assertEquals(List.of("#OUTPUT,5,1,0,3.50"), harness.takeSent());
    }

    @Test
    public void defaultFadesAreOneSecond() {
        startOnline(Map.of());
        command(OnOffType.ON);
        command(OnOffType.OFF);
        assertEquals(List.of("#OUTPUT,5,1,100,1", "#OUTPUT,5,1,0,1"), harness.takeSent());
    }

    @Test
    public void onToLastRestoresTheLastNonZeroLevel() {
        startOnline(Map.of("onToLast", true));
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "65.00");
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "0.00");

        command(OnOffType.ON);

        assertEquals(List.of("#OUTPUT,5,1,65.00,1"), harness.takeSent());
    }

    @Test
    public void onToLastBeforeAnyLevelUsesTheOnLevel() {
        startOnline(Map.of("onToLast", true, "onLevel", 70));
        command(OnOffType.ON);
        assertEquals(List.of("#OUTPUT,5,1,70,1"), harness.takeSent());
    }

    @Test
    public void setLightLevelFromRuleAction() {
        startOnline(Map.of());
        handler.setLightLevel(new BigDecimal("55.7"), new LutronDuration(4), new LutronDuration(10));
        assertEquals(List.of("#OUTPUT,5,1,55,4,10"), harness.takeSent());
    }

    @Test
    public void linkingTheChannelRefreshesIt() {
        startOnline(Map.of());
        handler.channelLinked(harness.channel(CHANNEL_LIGHTLEVEL));
        handler.channelLinked(harness.channel("other"));
        assertEquals(List.of("?OUTPUT,5,1"), harness.takeSent());
    }

    @Test
    public void missingIntegrationIdIsAConfigurationError() {
        harness = new ChildHandlerHarness("dimmer", Map.of());
        handler = new DimmerHandler(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();

        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, harness.status().getStatusDetail());
        assertTrue(harness.sent.isEmpty());
        assertThrows(IllegalStateException.class, () -> new DimmerHandler(harness.thing).getIntegrationId());
    }

    @Test
    public void offlineBridgeAtStartupWaitsAndThenQueries() {
        Map<String, Object> config = Map.of("integrationId", 5);
        harness = new ChildHandlerHarness("dimmer", config);
        harness.setBridgeStatus(ThingStatus.OFFLINE);
        handler = new DimmerHandler(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();

        assertEquals(ThingStatusDetail.BRIDGE_OFFLINE, harness.status().getStatusDetail());
        assertTrue(harness.sent.isEmpty());

        harness.setBridgeStatus(ThingStatus.ONLINE);
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));

        assertEquals(ThingStatus.UNKNOWN, harness.status().getStatus());
        assertEquals(List.of("?OUTPUT,5,1"), harness.takeSent());
    }

    @Test
    public void bridgeGoingOfflineTakesTheDimmerOffline() {
        startOnline(Map.of());
        handler.bridgeStatusChanged(
                new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, null));
        assertEquals(ThingStatusDetail.BRIDGE_OFFLINE, harness.status().getStatusDetail());
    }

    @Test
    public void unrelatedUpdatesAreIgnored() {
        startOnline(Map.of());
        clearInvocations(harness.callback);

        handler.handleUpdate(LutronCommandType.OUTPUT, "2");
        handler.handleUpdate(LutronCommandType.OUTPUT, "1");
        handler.handleUpdate(LutronCommandType.OUTPUT, "29", "1");
        handler.handleUpdate(LutronCommandType.DEVICE, "1", "50");

        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void malformedLevelsAreRejected() {
        startOnline(Map.of());
        clearInvocations(harness.callback);

        // The bridge handler catches these, so they only need to be rejected without a state update
        assertThrows(NumberFormatException.class, () -> handler.handleUpdate(LutronCommandType.OUTPUT, "1", "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> handler.handleUpdate(LutronCommandType.OUTPUT, "1", "150.00"));
        assertThrows(IllegalArgumentException.class, () -> handler.handleUpdate(LutronCommandType.OUTPUT, "1", "-1"));

        verify(harness.callback, never()).stateUpdated(any(), any());
    }
}
