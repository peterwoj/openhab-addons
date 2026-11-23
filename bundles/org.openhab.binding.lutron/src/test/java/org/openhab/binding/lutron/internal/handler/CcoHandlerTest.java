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
import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_SWITCH;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.types.RefreshType;

/**
 * Tests for {@link CcoHandler} and its pulsed and maintained variants.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class CcoHandlerTest {

    private @NonNullByDefault({}) ChildHandlerHarness harness;

    private CcoHandler start(Map<String, Object> extra, Function<Thing, CcoHandler> factory) {
        Map<String, Object> config = new HashMap<>();
        config.put("integrationId", 4);
        config.putAll(extra);
        harness = new ChildHandlerHarness("cco", config);
        CcoHandler handler = factory.apply(harness.thing);
        handler.setCallback(harness.callback);
        handler.initialize();
        return handler;
    }

    private CcoHandler start(Map<String, Object> extra) {
        return start(extra, CcoHandler::new);
    }

    @Test
    public void pulsedOnSendsAPulseAndResetsTheSwitch() {
        CcoHandler handler = start(Map.of());
        assertEquals(List.of("?OUTPUT,4,1"), harness.takeSent());

        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);

        assertEquals(List.of("#OUTPUT,4,6,0.50"), harness.takeSent());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
    }

    @Test
    public void pulsedOffAndRefreshSendNothing() {
        CcoHandler handler = start(Map.of("outputType", "Pulsed"));
        harness.takeSent();

        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), RefreshType.REFRESH);

        assertTrue(harness.sent.isEmpty());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
    }

    @Test
    public void configuredPulseLength() {
        CcoHandler handler = start(Map.of("pulseLength", 2.5));
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(List.of("#OUTPUT,4,6,2.50"), harness.takeSent());
    }

    @Test
    public void outOfRangePulseLengthFallsBackToTheDefault() {
        CcoHandler handler = start(Map.of("pulseLength", 120));
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(List.of("#OUTPUT,4,6,0.50"), harness.takeSent());

        handler = start(Map.of("pulseLength", -1));
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(List.of("#OUTPUT,4,6,0.50"), harness.takeSent());
    }

    @Test
    public void unknownOutputTypeIsTreatedAsPulsed() {
        CcoHandler handler = start(Map.of("outputType", "Latching"));
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(List.of("#OUTPUT,4,6,0.50"), harness.takeSent());
    }

    @Test
    public void pulsedUpdatesOnlyBringTheThingOnline() {
        CcoHandler handler = start(Map.of());
        clearInvocations(harness.callback);

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "100");

        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void maintainedCommandsSetTheContactState() {
        CcoHandler handler = start(Map.of("outputType", "Maintained"));
        harness.takeSent();

        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), RefreshType.REFRESH);

        assertEquals(List.of("#OUTPUT,4,1,100", "#OUTPUT,4,1,0", "?OUTPUT,4,1"), harness.takeSent());
    }

    @Test
    public void maintainedUpdatesSetTheSwitch() {
        CcoHandler handler = start(Map.of(), MaintainedCcoHandler::new);

        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "100");
        handler.handleUpdate(LutronCommandType.OUTPUT, "1", "0");

        assertEquals(ThingStatus.ONLINE, harness.status().getStatus());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);
    }

    @Test
    public void maintainedIgnoresUnparsableState() {
        CcoHandler handler = start(Map.of(), MaintainedCcoHandler::new);
        clearInvocations(harness.callback);

        assertDoesNotThrow(() -> handler.handleUpdate(LutronCommandType.OUTPUT, "1", "closed"));
        handler.handleUpdate(LutronCommandType.OUTPUT, "6", "1");

        verify(harness.callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void pulsedSubclassIgnoresTheConfiguredType() {
        CcoHandler handler = start(Map.of("outputType", "Maintained"), PulsedCcoHandler::new);
        harness.takeSent();
        handler.handleCommand(harness.channel(CHANNEL_SWITCH), OnOffType.ON);
        assertEquals(List.of("#OUTPUT,4,6,0.50"), harness.takeSent());
    }

    @Test
    public void linkingTheSwitch() {
        CcoHandler pulsed = start(Map.of());
        harness.takeSent();
        pulsed.channelLinked(harness.channel(CHANNEL_SWITCH));
        assertTrue(harness.sent.isEmpty());
        verify(harness.callback).stateUpdated(harness.channel(CHANNEL_SWITCH), OnOffType.OFF);

        CcoHandler maintained = start(Map.of("outputType", "Maintained"));
        harness.takeSent();
        maintained.channelLinked(harness.channel(CHANNEL_SWITCH));
        assertEquals(List.of("?OUTPUT,4,1"), harness.takeSent());
    }
}
