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
package org.openhab.binding.lutron.internal.grxprg;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openhab.binding.lutron.internal.net.FakeTelnetServer;

/**
 * Tests for {@link SocketSession}, the line-oriented connection used by the GRAFIK Eye PRG bridge.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SocketSessionTest {
    private static final long WAIT_SECONDS = FakeTelnetServer.WAIT_SECONDS;

    // SocketSessionCallback has no null annotations, so its parameters stay unconstrained here
    @NonNullByDefault({})
    private static class RecordingCallback implements SocketSessionCallback {
        final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        final BlockingQueue<Exception> exceptions = new LinkedBlockingQueue<>();

        @Override
        public void responseReceived(String response) {
            if ("explode".equals(response)) {
                throw new IllegalStateException("callback failure");
            }
            responses.add(response);
        }

        @Override
        public void responseException(Exception exception) {
            exceptions.add(exception);
        }

        String next() throws InterruptedException {
            String response = responses.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(response, "no response dispatched");
            return response;
        }
    }

    private @NonNullByDefault({}) FakeTelnetServer server;
    private @NonNullByDefault({}) SocketSession session;
    private final RecordingCallback callback = new RecordingCallback();

    @BeforeEach
    public void setUp() throws IOException {
        server = new FakeTelnetServer();
        session = new SocketSession("socket-session-test", "127.0.0.1", server.port());
    }

    @AfterEach
    public void tearDown() throws IOException {
        session.disconnect();
        server.close();
    }

    private static long sessionThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("OH-binding-socket-session-test-")).count();
    }

    @Test
    public void constructorRejectsBadAddresses() {
        assertThrows(IllegalArgumentException.class, () -> new SocketSession("x", "", 23));
        assertThrows(IllegalArgumentException.class, () -> new SocketSession("x", "  ", 23));
        assertThrows(IllegalArgumentException.class, () -> new SocketSession("x", "host", 0));
        assertThrows(IllegalArgumentException.class, () -> new SocketSession("x", "host", 65536));
    }

    @Test
    public void sendingWhileDisconnectedFails() {
        assertFalse(session.isConnected());
        assertThrows(IOException.class, () -> session.sendCommand("G"));
    }

    @Test
    public void commandsEndWithABlankLine() throws Exception {
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();

        session.sendCommand("A51");

        assertEquals("A51", prg.expectLine());
        assertEquals("", prg.expectLine());
    }

    @Test
    public void responsesAreSplitOnCrLfAndTheLoginPrompt() throws Exception {
        session.setCallback(callback);
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();

        prg.send("login: ");
        prg.send("~:ss 11111111\r\n~1 OK\r\npartial");

        assertEquals("login", callback.next());
        assertEquals("~:ss 11111111", callback.next());
        assertEquals("~1 OK", callback.next());
        assertNull(callback.responses.poll(500, TimeUnit.MILLISECONDS), "a partial response must not be dispatched");

        prg.send(" line\r\n");
        assertEquals("partial line", callback.next());
    }

    @Test
    public void responsesWaitUntilACallbackIsSet() throws Exception {
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();
        prg.send("~1 OK\r\n");
        Thread.sleep(500);

        session.setCallback(callback);

        assertEquals("~1 OK", callback.next());
    }

    @Test
    public void responseAfterACallbackChangeGoesToTheNewCallback() throws Exception {
        RecordingCallback next = new RecordingCallback();
        session.setCallback(callback);
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();
        prg.send("~1 OK\r\n");
        assertEquals("~1 OK", callback.next());

        session.setCallback(next);
        prg.send("~2 OK\r\n");

        assertEquals("~2 OK", next.next());
        assertNull(callback.responses.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void failingCallbackDoesNotStopDispatch() throws Exception {
        session.setCallback(callback);
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();

        prg.send("explode\r\n~2 OK\r\n");

        assertEquals("~2 OK", callback.next());
    }

    @Test
    public void closedConnectionIsReportedOnce() throws Exception {
        session.setCallback(callback);
        session.connect();
        server.accept().close();

        Exception e = callback.exceptions.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(e, "the closed connection was not reported");
        assertTrue(String.valueOf(e.getMessage()).contains("server closed connection"), e.toString());
        assertNull(callback.exceptions.poll(1, TimeUnit.SECONDS));
    }

    @Test
    public void disconnectStopsTheWorkerThreads() throws Exception {
        session.setCallback(callback);
        session.connect();
        FakeTelnetServer.Connection prg = server.accept();
        prg.send("~1 OK\r\n");
        callback.next(); // both workers are running

        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS), session::disconnect);

        assertFalse(session.isConnected());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (sessionThreads() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(0, sessionThreads(), "worker threads kept running after disconnect");
    }

    @Test
    public void immediateDisconnectLeavesNoWorkerThreads() throws Exception {
        for (int i = 0; i < 20; i++) {
            session.connect();
            session.disconnect();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (sessionThreads() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(0, sessionThreads(), "worker threads kept running after disconnect");
    }

    @Test
    public void reconnectReplacesTheConnection() throws Exception {
        session.setCallback(callback);
        session.connect();
        FakeTelnetServer.Connection first = server.accept();
        first.send("~1 OK\r\n");
        callback.next();

        session.connect();
        session.setCallback(callback);
        FakeTelnetServer.Connection second = server.accept();
        assertTrue(first.isClosedByPeer(), "the first connection was not closed");

        second.send("~2 OK\r\n");
        assertEquals("~2 OK", callback.next());
        session.sendCommand("G");
        assertEquals("G", second.expectLine());
    }
}
