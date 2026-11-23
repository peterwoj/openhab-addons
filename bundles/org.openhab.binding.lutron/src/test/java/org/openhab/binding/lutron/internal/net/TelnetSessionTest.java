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
package org.openhab.binding.lutron.internal.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link TelnetSession} against a telnet peer on the loopback interface.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TelnetSessionTest {
    private static final long WAIT_SECONDS = FakeTelnetServer.WAIT_SECONDS;

    private @NonNullByDefault({}) FakeTelnetServer server;
    private final TelnetSession session = new TelnetSession();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    @BeforeEach
    public void setUp() throws IOException {
        server = new FakeTelnetServer();
    }

    @AfterEach
    public void tearDown() throws IOException {
        session.close();
        server.close();
    }

    /**
     * Collects complete lines the way IPBridgeHandler does: by draining the session each time input arrives.
     */
    private void collectLines() {
        session.addListener(new TelnetSessionListener() {
            @Override
            public void inputAvailable() {
                for (String line : session.readLines()) {
                    lines.add(line);
                }
            }

            @Override
            public void error(IOException exception) {
            }
        });
    }

    private String nextLine() throws InterruptedException {
        String line = lines.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(line, "no line received");
        return line;
    }

    @Test
    public void connectionStateFollowsOpenAndClose() throws Exception {
        assertFalse(session.isConnected());
        session.open("127.0.0.1", server.port());
        server.accept();
        assertTrue(session.isConnected());
        session.close();
        assertFalse(session.isConnected());
    }

    @Test
    public void openToAClosedPortFails() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        assertThrows(IOException.class, () -> session.open("127.0.0.1", closedPort));
        assertFalse(session.isConnected());
    }

    @Test
    public void writeLineIsTerminatedWithCrLf() throws Exception {
        session.open("127.0.0.1", server.port());
        FakeTelnetServer.Connection peer = server.accept();
        session.writeLine("#OUTPUT,1,1,50");
        session.writeLine("?OUTPUT,1,1");
        assertEquals("#OUTPUT,1,1,50", peer.expectLine());
        assertEquals("?OUTPUT,1,1", peer.expectLine());
    }

    @Test
    public void writeLineWhenClosedFails() throws Exception {
        assertThrows(IOException.class, () -> session.writeLine("#OUTPUT,1,1,50"));
        session.open("127.0.0.1", server.port());
        server.accept();
        session.close();
        assertThrows(IOException.class, () -> session.writeLine("#OUTPUT,1,1,50"));
    }

    @Test
    public void completeLinesAreDeliveredAndPartialLinesCarryOver() throws Exception {
        collectLines();
        session.open("127.0.0.1", server.port());
        FakeTelnetServer.Connection peer = server.accept();

        peer.send("~OUTPUT,1,1,10.00\r\n~OUTPUT,2,1,");
        assertEquals("~OUTPUT,1,1,10.00", nextLine());
        assertNull(lines.poll(300, TimeUnit.MILLISECONDS), "a partial line must not be delivered");

        peer.send("20.00\r\n");
        assertEquals("~OUTPUT,2,1,20.00", nextLine());
    }

    @Test
    public void severalLinesInOneReadArriveInOrder() throws Exception {
        collectLines();
        session.open("127.0.0.1", server.port());
        FakeTelnetServer.Connection peer = server.accept();

        peer.send("~A\r\n~B\r\n~C\r\n");

        assertEquals("~A", nextLine());
        assertEquals("~B", nextLine());
        assertEquals("~C", nextLine());
    }

    @Test
    public void waitForMatchesAPromptThatHasAlreadyArrived() throws Exception {
        session.open("127.0.0.1", server.port());
        FakeTelnetServer.Connection peer = server.accept();
        peer.send("Welcome\r\nlogin: ");

        // The prompt may already be buffered when waitFor is called
        Thread.sleep(200);
        MatchResult result = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                () -> session.waitFor("login:"));
        assertEquals("login:", result.group());
    }

    @Test
    public void waitForMatchesAPromptThatArrivesLater() throws Exception {
        session.open("127.0.0.1", server.port());
        FakeTelnetServer.Connection peer = server.accept();

        Thread sender = new Thread(() -> {
            try {
                Thread.sleep(300);
                peer.send("password: ");
            } catch (InterruptedException | IOException e) {
                // the test fails on its own timeout
            }
        }, "test-sender");
        sender.setDaemon(true);
        sender.start();

        MatchResult result = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                () -> session.waitFor("password:"));
        assertEquals("password:", result.group());
    }

    @Test
    public void reopenedSessionStillSeesThePrompt() throws Exception {
        collectLines();
        // The stall depends on thread timing, so reconnect often enough to make it likely
        for (int i = 0; i < 200; i++) {
            session.open("127.0.0.1", server.port());
            FakeTelnetServer.Connection peer = server.accept();
            peer.send("Welcome\r\nlogin: ");

            // Bounded from outside, because a stalled session can block waitFor() before its own timeout applies
            MatchResult result = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                    () -> session.waitFor("login:", TimeUnit.SECONDS.toMillis(5)));

            assertTrue(result.hasMatch(), "the login prompt was not seen after reopening " + i + " times");
            session.close();
            peer.close();
        }
    }

    @Test
    public void closingTheSessionEndsAWaitWithoutTimeout() throws Exception {
        session.open("127.0.0.1", server.port());
        server.accept();
        BlockingQueue<MatchResult> result = new LinkedBlockingQueue<>();
        Thread waiter = new Thread(() -> {
            try {
                result.add(session.waitFor("login:"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-waiter");
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(300); // let the waiter block

        session.close();

        MatchResult outcome = result.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(outcome, "waitFor() kept waiting after the session was closed");
        assertFalse(outcome.hasMatch());
    }

    @Test
    public void waitForWithATimeoutGivesUp() throws Exception {
        session.open("127.0.0.1", server.port());
        server.accept().send("something else");

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS), () -> session.waitFor("login:", 300));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(250), "returned before the timeout");
    }
}
