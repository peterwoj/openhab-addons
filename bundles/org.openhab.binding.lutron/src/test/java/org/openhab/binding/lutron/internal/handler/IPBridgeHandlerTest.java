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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openhab.binding.lutron.internal.net.FakeTelnetServer;
import org.openhab.binding.lutron.internal.protocol.LIPCommand;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.binding.lutron.internal.protocol.lip.LutronOperation;
import org.openhab.binding.lutron.internal.protocol.lip.Monitoring;
import org.openhab.binding.lutron.internal.protocol.lip.TargetType;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Tests for {@link IPBridgeHandler} against a fake LIP bridge (RadioRA 2, HomeWorks QS, Caseta Pro) on the loopback
 * interface.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class IPBridgeHandlerTest {
    private static final long WAIT_MS = TimeUnit.SECONDS.toMillis(FakeTelnetServer.WAIT_SECONDS);

    /** Prompt off, the nine required monitoring types, and the database date query */
    private static final int STARTUP_COMMANDS = 11;

    private final Bridge bridge = mock(Bridge.class);
    private final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    private final List<Thing> children = new ArrayList<>();
    private @NonNullByDefault({}) FakeTelnetServer server;
    private @NonNullByDefault({}) IPBridgeHandler handler;

    @BeforeEach
    public void setUp() throws IOException {
        server = new FakeTelnetServer();
        when(bridge.getUID()).thenReturn(new ThingUID("lutron:ipbridge:test"));
        when(bridge.getStatusInfo()).thenReturn(new ThingStatusInfo(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, null));
        when(bridge.getThings()).thenReturn(children);
    }

    @AfterEach
    public void tearDown() throws IOException {
        IPBridgeHandler current = handler;
        if (current != null) {
            assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS), current::dispose);
        }
        server.close();
    }

    private void start(Map<String, Object> extraConfig) {
        Map<String, Object> config = new HashMap<>();
        config.put("ipAddress", "127.0.0.1");
        config.put("user", "lutron");
        config.put("password", "integration");
        config.putAll(extraConfig);
        when(bridge.getConfiguration()).thenReturn(new Configuration(config));
        handler = new IPBridgeHandler(bridge);
        handler.setCallback(callback);
        handler.telnetPort = server.port();
        handler.initialize();
    }

    /**
     * Plays the bridge's side of a successful login, ending with the given prompt.
     */
    private FakeTelnetServer.Connection login(String prompt) throws Exception {
        FakeTelnetServer.Connection peer = server.accept();
        peer.send("login: ");
        assertEquals("lutron", peer.expectLine());
        peer.send("password: ");
        assertEquals("integration", peer.expectLine());
        peer.send("\r\n" + prompt + " ");
        return peer;
    }

    private List<String> readLines(FakeTelnetServer.Connection peer, int count) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(peer.expectLine());
        }
        return lines;
    }

    private FakeTelnetServer.Connection online() throws Exception {
        start(Map.of());
        FakeTelnetServer.Connection peer = login("GNET>");
        verifyOnline(1);
        readLines(peer, STARTUP_COMMANDS);
        return peer;
    }

    private void verifyOnline(int times) {
        verify(callback, timeout(WAIT_MS).times(times)).statusUpdated(eq(bridge),
                argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    private void verifyStatus(ThingStatusDetail detail) {
        verify(callback, timeout(WAIT_MS)).statusUpdated(eq(bridge),
                argThat(info -> info.getStatus() == ThingStatus.OFFLINE && info.getStatusDetail() == detail));
    }

    private LutronHandler addChild(int integrationId) {
        LutronHandler child = mock(LutronHandler.class);
        when(child.getIntegrationId()).thenReturn(integrationId);
        Thing thing = mock(Thing.class);
        when(thing.getHandler()).thenReturn(child);
        children.add(thing);
        return child;
    }

    private void sendMarker() {
        handler.sendCommand(
                new LIPCommand(TargetType.BRIDGE, LutronOperation.QUERY, LutronCommandType.SYSTEM, null, 1));
    }

    @Test
    public void loginSendsTheStartupCommandsAndGoesOnline() throws Exception {
        start(Map.of());
        FakeTelnetServer.Connection peer = login("GNET>");
        verifyOnline(1);

        List<String> startup = readLines(peer, STARTUP_COMMANDS);

        assertEquals("#MONITORING,12,2", startup.get(0), "prompts are disabled first");
        Set<String> monitoring = new HashSet<>(startup.subList(1, 10));
        Set<String> expected = new HashSet<>();
        for (Integer type : Monitoring.REQUIRED_SET) {
            expected.add("#MONITORING," + type + ",1");
        }
        assertEquals(expected, monitoring);
        assertEquals("?SYSTEM,10", startup.get(10));
    }

    @Test
    public void quantumPromptIsAccepted() throws Exception {
        start(Map.of());
        login("QNET>");
        verifyOnline(1);
    }

    @Test
    public void defaultCredentialsAreUsedWhenNoneAreConfigured() throws Exception {
        when(bridge.getConfiguration()).thenReturn(new Configuration(Map.of("ipAddress", "127.0.0.1")));
        handler = new IPBridgeHandler(bridge);
        handler.setCallback(callback);
        handler.telnetPort = server.port();
        handler.initialize();

        login("GNET>");
        verifyOnline(1);
    }

    @Test
    public void repeatedLoginPromptIsAnswered() throws Exception {
        start(Map.of());
        FakeTelnetServer.Connection peer = server.accept();
        peer.send("login: ");
        assertEquals("lutron", peer.expectLine());
        peer.send("password: ");
        assertEquals("integration", peer.expectLine());
        peer.send("\r\nlogin: ");
        assertEquals("lutron", peer.expectLine());
        peer.send("password: ");
        assertEquals("integration", peer.expectLine());
        peer.send("\r\nGNET> ");

        verifyOnline(1);
    }

    @Test
    public void rejectedPasswordIsAConfigurationError() throws Exception {
        start(Map.of());
        FakeTelnetServer.Connection peer = server.accept();
        for (int attempt = 0; attempt < 2; attempt++) {
            peer.send("login: ");
            assertEquals("lutron", peer.expectLine());
            peer.send("password: ");
            assertEquals("integration", peer.expectLine());
            peer.send("\r\n");
        }
        peer.send("login: ");

        verifyStatus(ThingStatusDetail.CONFIGURATION_ERROR);
        verify(callback, never()).statusUpdated(any(), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    @Test
    public void safeModeIsACommunicationError() throws Exception {
        start(Map.of());
        login("SAFE>");

        verifyStatus(ThingStatusDetail.COMMUNICATION_ERROR);
        verify(callback, never()).statusUpdated(any(), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    @Test
    public void missingAddressIsAConfigurationError() throws InterruptedException {
        when(bridge.getConfiguration()).thenReturn(new Configuration(Map.of()));
        handler = new IPBridgeHandler(bridge);
        handler.setCallback(callback);
        handler.initialize();

        verifyStatus(ThingStatusDetail.CONFIGURATION_ERROR);
        assertNull(server.pollAccept(500), "no connection must be attempted");
    }

    @Test
    public void outputUpdateReachesTheChildWithThatIntegrationId() throws Exception {
        LutronHandler dimmer = addChild(5);
        LutronHandler other = addChild(6);
        FakeTelnetServer.Connection peer = online();

        peer.send("~OUTPUT,5,1,42.00\r\n");

        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "42.00");
        verify(other, never()).handleUpdate(any(), any(String[].class));
    }

    @Test
    public void everyUpdateTypeIsDispatched() throws Exception {
        LutronHandler child = addChild(7);
        FakeTelnetServer.Connection peer = online();

        peer.send("~DEVICE,7,3,3\r\n~TIMECLOCK,7,1,2\r\n~MODE,7,1,3\r\n~SYSVAR,7,1,4\r\n~GROUP,7,3,3\r\n");

        verify(child, timeout(WAIT_MS)).handleUpdate(LutronCommandType.DEVICE, "3", "3");
        verify(child, timeout(WAIT_MS)).handleUpdate(LutronCommandType.TIMECLOCK, "1", "2");
        verify(child, timeout(WAIT_MS)).handleUpdate(LutronCommandType.MODE, "1", "3");
        verify(child, timeout(WAIT_MS)).handleUpdate(LutronCommandType.SYSVAR, "1", "4");
        verify(child, timeout(WAIT_MS)).handleUpdate(LutronCommandType.GROUP, "3", "3");
    }

    @Test
    public void promptEmbeddedInAResponseIsIgnored() throws Exception {
        LutronHandler dimmer = addChild(5);
        FakeTelnetServer.Connection peer = online();

        peer.send("GNET> ~OUTPUT,5,1,10.00\r\nQNET> QNET> ~OUTPUT,5,1,20.00\r\n");

        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "10.00");
        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "20.00");
    }

    @Test
    public void garbageIsIgnoredAndTheSessionKeepsWorking() throws Exception {
        LutronHandler dimmer = addChild(5);
        FakeTelnetServer.Connection peer = online();

        peer.sendBytes(new byte[] { 0x00, 0x01, 0x07, (byte) 0x80, (byte) 0xFE, '\r', '\n' });
        peer.send("random noise\r\n");
        peer.send("~OUTPUT,abc,1,5\r\n");
        peer.send("~OUTPUT,5,1,fifty\r\n");
        peer.send("~BOGUS,5,1,5\r\n");
        peer.send("~OUTPUT,99999999999,1,5\r\n");
        peer.send("~SYSTEM,10,1/2\r\n");
        peer.send("~SYSTEM,10\r\n");
        peer.send("\r\n\r\n");
        peer.send("~ERROR,6\r\n");
        peer.send("~OUTPUT,5,1,77.00\r\n");

        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "77.00");
        verify(dimmer, times(1)).handleUpdate(any(), any(String[].class));
    }

    @Test
    public void childThatRejectsAnUpdateDoesNotStopDispatch() throws Exception {
        LutronHandler dimmer = addChild(5);
        doThrow(new NumberFormatException("bad")).doNothing().when(dimmer).handleUpdate(any(), any(String[].class));
        FakeTelnetServer.Connection peer = online();

        peer.send("~OUTPUT,5,1,1.2.3\r\n~OUTPUT,5,1,30.00\r\n");

        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "30.00");
    }

    @Test
    public void sysvarMonitoringIsEnabledOnceForSeveralSysvarThings() throws Exception {
        FakeTelnetServer.Connection peer = online();

        handler.childHandlerInitialized(mock(SysvarHandler.class), mock(Thing.class));
        handler.childHandlerInitialized(mock(SysvarHandler.class), mock(Thing.class));
        handler.childHandlerInitialized(mock(LutronHandler.class), mock(Thing.class));
        sendMarker();

        assertEquals("#MONITORING,10,1", peer.expectLine());
        assertEquals("?SYSTEM,1", peer.expectLine());
    }

    @Test
    public void concurrentSendersDeliverEveryCommandExactlyOnce() throws Exception {
        FakeTelnetServer.Connection peer = online();
        final int threads = 8;
        final int perThread = 50;
        CyclicBarrier startLine = new CyclicBarrier(threads);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> senders = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = 100 + t;
            Thread sender = new Thread(() -> {
                try {
                    startLine.await(WAIT_MS, TimeUnit.MILLISECONDS);
                    for (int n = 0; n < perThread; n++) {
                        handler.sendCommand(new LIPCommand(TargetType.DIMMER, LutronOperation.EXECUTE,
                                LutronCommandType.OUTPUT, id, 1, n));
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "test-sender");
            sender.setDaemon(true);
            senders.add(sender);
            sender.start();
        }
        for (Thread sender : senders) {
            sender.join(WAIT_MS);
        }
        assertTrue(failures.isEmpty(), () -> "sender failed: " + failures);

        Set<String> expected = new HashSet<>();
        for (int t = 0; t < threads; t++) {
            for (int n = 0; n < perThread; n++) {
                expected.add("#OUTPUT," + (100 + t) + ",1," + n);
            }
        }
        List<String> received = readLines(peer, threads * perThread);
        assertEquals(expected, new HashSet<>(received), "a command was lost, duplicated or garbled");
        assertEquals(threads * perThread, received.size());
    }

    @Test
    public void configuredDelayThrottlesTheSendRate() throws Exception {
        final int delayMs = 150;
        start(Map.of("delay", delayMs));
        FakeTelnetServer.Connection peer = login("GNET>");
        verifyOnline(1);
        readLines(peer, STARTUP_COMMANDS);

        long enqueued = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            handler.sendCommand(
                    new LIPCommand(TargetType.DIMMER, LutronOperation.EXECUTE, LutronCommandType.OUTPUT, 1, 1, i));
        }
        readLines(peer, 5);

        // Only a lower bound is checked, because a loaded build machine can make sending arbitrarily slow
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueued);
        assertTrue(elapsedMs >= 4 * delayMs, "five commands arrived after only " + elapsedMs + " ms");
    }

    @Test
    public void slowBridgeDoesNotBlockCallers() throws Exception {
        FakeTelnetServer.Connection peer = online();
        String bulk = "X".repeat(8_192);
        final int commands = 400;

        // The bridge stops reading, so the sender thread blocks once the socket buffers are full
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < commands; i++) {
                handler.sendCommand(new LIPCommand(TargetType.BRIDGE, LutronOperation.EXECUTE, LutronCommandType.SYSTEM,
                        null, i, bulk));
            }
        });

        for (int i = 0; i < commands; i++) {
            assertEquals("#SYSTEM," + i + "," + bulk, peer.expectLine());
        }
    }

    @Test
    public void answeredKeepaliveKeepsTheConnection() throws Exception {
        LutronHandler dimmer = addChild(5);
        FakeTelnetServer.Connection peer = online();
        handler.keepAliveTimeoutMs = 1_000;

        handler.sendKeepAlive();
        assertEquals("?SYSTEM,10", peer.expectLine());
        peer.send("~SYSTEM,10,11/23/2026,10:00:00\r\n");

        // Long enough for the keepalive timeout to have fired if the reply had not cancelled it
        assertNull(server.pollAccept(3_000), "the bridge reconnected although the keepalive was answered");
        peer.send("~OUTPUT,5,1,12.00\r\n");
        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "12.00");
    }

    @Test
    public void unansweredKeepaliveReconnects() throws Exception {
        LutronHandler dimmer = addChild(5);
        FakeTelnetServer.Connection first = online();
        handler.keepAliveTimeoutMs = 300;

        handler.sendKeepAlive();
        assertEquals("?SYSTEM,10", first.expectLine());

        FakeTelnetServer.Connection second = login("GNET>");
        verifyOnline(2);
        readLines(second, STARTUP_COMMANDS);
        assertTrue(first.isClosedByPeer(), "the old connection was not closed");

        second.send("~OUTPUT,5,1,64.00\r\n");
        verify(dimmer, timeout(WAIT_MS)).handleUpdate(LutronCommandType.OUTPUT, "1", "64.00");
    }

    @Test
    public void droppedConnectionIsRecoveredByTheKeepalive() throws Exception {
        FakeTelnetServer.Connection first = online();
        handler.keepAliveTimeoutMs = 300;

        first.close();
        handler.sendKeepAlive();

        FakeTelnetServer.Connection second = login("GNET>");
        verifyOnline(2);
        assertEquals("#MONITORING,12,2", second.expectLine());
    }

    @Test
    public void disposeClosesTheConnectionPromptly() throws Exception {
        FakeTelnetServer.Connection peer = online();

        assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS), handler::dispose);

        assertNull(peer.readLine(), "the connection was not closed");
    }
}
