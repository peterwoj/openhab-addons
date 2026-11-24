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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.lutron.internal.net.FakeTelnetServer;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.State;

/**
 * Tests for {@link PrgProtocolHandler} against a fake GRAFIK Eye PRG interface on the loopback interface.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PrgProtocolHandlerTest {
    private static final long WAIT_MS = TimeUnit.SECONDS.toMillis(FakeTelnetServer.WAIT_SECONDS);

    private final PrgHandlerCallback callback = mock(PrgHandlerCallback.class);
    private @NonNullByDefault({}) FakeTelnetServer server;
    private @NonNullByDefault({}) SocketSession session;
    private @NonNullByDefault({}) PrgProtocolHandler handler;
    private @NonNullByDefault({}) FakeTelnetServer.Connection prg;

    @BeforeEach
    public void setUp() throws Exception {
        server = new FakeTelnetServer();
        session = new SocketSession("test", "127.0.0.1", server.port());
        handler = new PrgProtocolHandler(session, callback);
        session.connect();
        prg = server.accept();
    }

    @AfterEach
    public void tearDown() throws IOException {
        session.disconnect();
        server.close();
    }

    /**
     * Runs the blocking login on a helper thread and returns its outcome: null on success, the failure message, or
     * the exception thrown.
     */
    private BlockingQueue<Object> loginInBackground() {
        BlockingQueue<Object> outcome = new LinkedBlockingQueue<>();
        Thread login = new Thread(() -> {
            try {
                String result = handler.login("lutron");
                outcome.add(result == null ? "OK" : result);
            } catch (Exception e) {
                outcome.add(e);
            }
        }, "test-login");
        login.setDaemon(true);
        login.start();
        return outcome;
    }

    private @Nullable Object outcome(BlockingQueue<Object> outcome) throws InterruptedException {
        return outcome.poll(WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the next command the handler sent, skipping the blank line the protocol puts after each command.
     */
    private String nextCommand() throws IOException {
        String line;
        do {
            line = prg.expectLine();
        } while (line.isEmpty());
        return line;
    }

    private void loggedIn() throws Exception {
        BlockingQueue<Object> outcome = loginInBackground();
        prg.send("login: ");
        assertEquals("lutron", nextCommand());
        prg.send("connection established\r\n");
        assertEquals("OK", outcome(outcome));
        clearInvocations(callback);
    }

    private void respond(String response) throws IOException {
        prg.send(response + "\r\n");
    }

    @Test
    public void loginGoesOnline() throws Exception {
        BlockingQueue<Object> outcome = loginInBackground();
        prg.send("login: ");
        assertEquals("lutron", nextCommand());
        prg.send("\r\nconnection established\r\n");

        assertEquals("OK", outcome(outcome));
        verify(callback).statusChanged(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
    }

    @Test
    public void silentInterfaceFailsTheLoginWithinItsTimeout() throws Exception {
        long start = System.nanoTime();
        BlockingQueue<Object> outcome = loginInBackground();

        Object result = outcome(outcome);

        assertInstanceOf(Exception.class, result);
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(WAIT_MS));
        verify(callback, never()).statusChanged(eq(ThingStatus.ONLINE), any(), any());
    }

    @Test
    public void unexpectedGreetingIsAProtocolViolation() throws Exception {
        BlockingQueue<Object> outcome = loginInBackground();
        prg.send("Welcome to something else\r\n");

        String result = assertInstanceOf(String.class, outcome(outcome));
        assertTrue(result.startsWith("Protocol violation"), result);
    }

    @Test
    public void rejectedLoginFails() throws Exception {
        BlockingQueue<Object> outcome = loginInBackground();
        prg.send("login: ");
        nextCommand();
        prg.send("~ERROR # 101 OK\r\n");

        assertEquals("login failed", outcome(outcome));
        verify(callback, never()).statusChanged(eq(ThingStatus.ONLINE), any(), any());
    }

    @Test
    public void firstResponseAfterLoginIsProcessed() throws Exception {
        BlockingQueue<Object> outcome = loginInBackground();
        prg.send("login: ");
        assertEquals("lutron", nextCommand());
        // The interface may answer straight away, before the binding is done switching to normal processing
        prg.send("connection established\r\n~:rs 2 1 OK\r\n");
        assertEquals("OK", outcome(outcome));

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SCHEDULE, new DecimalType(2));
    }

    @Test
    public void responseRightAfterLoginIsProcessed() throws Exception {
        loggedIn();

        respond("~:rs 1 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SCHEDULE, new DecimalType(1));
    }

    @Test
    public void sceneStatusReportsEachPresentControlUnit() throws Exception {
        loggedIn();

        respond(":ss 1M3AMMMM");

        verify(callback, timeout(WAIT_MS)).stateChanged(1, PrgConstants.CHANNEL_SCENE, new DecimalType(1));
        verify(callback, timeout(WAIT_MS)).stateChanged(3, PrgConstants.CHANNEL_SCENE, new DecimalType(3));
        verify(callback, timeout(WAIT_MS)).stateChanged(4, PrgConstants.CHANNEL_SCENE, new DecimalType(10));
        assertEquals(List.of("rzi 1", "rzi 3", "rzi 4"), List.of(nextCommand(), nextCommand(), nextCommand()));
        verify(callback, times(3)).stateChanged(anyInt(), eq(PrgConstants.CHANNEL_SCENE), any());
    }

    @Test
    public void unknownSceneCharacterIsSkipped() throws Exception {
        loggedIn();

        respond("~:ss Z2MMMMMM 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(2, PrgConstants.CHANNEL_SCENE, new DecimalType(2));
        verify(callback, never()).stateChanged(eq(1), eq(PrgConstants.CHANNEL_SCENE), any());
    }

    @Test
    public void zoneIntensityReportsZonesOneToSeven() throws Exception {
        loggedIn();

        // Zone 4 and zone 8 report their own index minus one, which the protocol uses for "zone not present"
        respond("~:zi 1 40 27 B 3 2 9 0 7 1 OK");

        String channel = PrgConstants.CHANNEL_ZONEINTENSITY;
        verify(callback, timeout(WAIT_MS)).stateChanged(1, channel + 7, new PercentType(0));
        verify(callback).stateChanged(1, channel + 1, new PercentType(50));
        verify(callback).stateChanged(1, channel + 2, new PercentType(30));
        verify(callback).stateChanged(1, channel + 3, new PercentType(8));
        verify(callback).stateChanged(1, channel + 5, new PercentType(1));
        verify(callback).stateChanged(1, channel + 6, new PercentType(7));
        verify(callback, never()).stateChanged(eq(1), eq(channel + 4), any());
        verify(callback, never()).stateChanged(eq(1), eq(channel + 8), any());
    }

    @Test
    public void zoneEightIntensityIsReported() throws Exception {
        loggedIn();

        respond("~:zi 1 0 1 2 3 4 5 6 40 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(1, PrgConstants.CHANNEL_ZONEINTENSITY + 8, new PercentType(50));
    }

    @Test
    public void zoneEightCanBeSteppedUpAndDown() throws Exception {
        loggedIn();

        handler.setZoneIntensity(1, 8, 0, true);
        handler.setZoneIntensity(1, 8, 0, true);
        handler.setZoneIntensity(1, 8, 0, false);

        assertEquals("szi 1 0 * * * * * * * 2", nextCommand());
        assertEquals("szi 1 0 * * * * * * * 3", nextCommand());
        assertEquals("szi 1 0 * * * * * * * 2", nextCommand());
    }

    @Test
    public void reportedTimeUsesTheCalendarDate() throws Exception {
        loggedIn();

        respond("~:rt 7 5 11 23 26 2 1 OK");

        ArgumentCaptor<State> captor = ArgumentCaptor.forClass(State.class);
        verify(callback, timeout(WAIT_MS)).stateChanged(eq(PrgConstants.CHANNEL_TIMECLOCK), captor.capture());
        ZonedDateTime time = ((DateTimeType) captor.getValue()).getInstant().atZone(ZoneId.systemDefault());
        assertEquals(ZonedDateTime.of(2026, 11, 23, 7, 5, 0, 0, ZoneId.systemDefault()), time);
    }

    @Test
    public void reportedTwoDigitYearsBeforeFiftyAreThisCentury() throws Exception {
        loggedIn();

        respond("~:rt 23 59 1 31 49 1 1 OK");
        respond("~:rt 0 0 12 1 99 1 1 OK");

        ArgumentCaptor<State> captor = ArgumentCaptor.forClass(State.class);
        verify(callback, timeout(WAIT_MS).times(2)).stateChanged(eq(PrgConstants.CHANNEL_TIMECLOCK), captor.capture());
        ZonedDateTime first = ((DateTimeType) captor.getAllValues().get(0)).getInstant().atZone(ZoneId.systemDefault());
        ZonedDateTime second = ((DateTimeType) captor.getAllValues().get(1)).getInstant()
                .atZone(ZoneId.systemDefault());
        assertEquals(ZonedDateTime.of(2049, 1, 31, 23, 59, 0, 0, ZoneId.systemDefault()), first);
        assertEquals(ZonedDateTime.of(1999, 12, 1, 0, 0, 0, 0, ZoneId.systemDefault()), second);
    }

    @Test
    public void controlUnitInformationSetsTheLocks() throws Exception {
        loggedIn();

        // The eighth field is a hex bit set: bit 1 scene sequence, bit 2 zone lock, bit 3 scene lock
        respond("~:mu 2 1 A B C D E 8 G");

        verify(callback, timeout(WAIT_MS)).stateChanged(2, PrgConstants.CHANNEL_ZONELOCK, OnOffType.OFF);
        verify(callback).stateChanged(2, PrgConstants.CHANNEL_SCENELOCK, OnOffType.ON);
        verify(callback).stateChanged(2, PrgConstants.CHANNEL_SCENESEQ, OnOffType.OFF);

        respond("~:mu 3 1 A B C D E 6 G");

        verify(callback, timeout(WAIT_MS)).stateChanged(3, PrgConstants.CHANNEL_ZONELOCK, OnOffType.ON);
        verify(callback).stateChanged(3, PrgConstants.CHANNEL_SCENELOCK, OnOffType.OFF);
        verify(callback).stateChanged(3, PrgConstants.CHANNEL_SCENESEQ, OnOffType.ON);
    }

    @Test
    public void setTimeSendsTheTimeclockFormat() throws Exception {
        loggedIn();
        Calendar monday = new GregorianCalendar(2026, Calendar.NOVEMBER, 23, 7, 5, 30);

        handler.setTime(monday);

        assertEquals("ST 7 05 11 23 26 2", nextCommand());
    }

    @Test
    public void shadeZoneIntensityUsesTheShadeTable() throws Exception {
        when(callback.isShade(2, 1)).thenReturn(true);
        loggedIn();

        respond("~:zi 2 71 1 2 3 4 5 6 7 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(2, PrgConstants.CHANNEL_ZONEINTENSITY + 1, new PercentType(3));
    }

    @Test
    public void unparsableZoneIntensityIsDropped() throws Exception {
        loggedIn();

        respond("~:zi 1 ZZ 1 2 3 4 5 6 7 1 OK");
        respond("~:rs 1 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SCHEDULE, new DecimalType(1));
        verify(callback, never()).stateChanged(anyInt(), startsWith(PrgConstants.CHANNEL_ZONEINTENSITY), any());
    }

    @Test
    public void sunriseAndSunset() throws Exception {
        loggedIn();

        respond("~:ra 6 30 19 45 1 OK");

        ArgumentCaptor<State> sunrise = ArgumentCaptor.forClass(State.class);
        ArgumentCaptor<State> sunset = ArgumentCaptor.forClass(State.class);
        verify(callback, timeout(WAIT_MS)).stateChanged(eq(PrgConstants.CHANNEL_SUNSET), sunset.capture());
        verify(callback).stateChanged(eq(PrgConstants.CHANNEL_SUNRISE), sunrise.capture());
        ZonedDateTime rise = ((DateTimeType) sunrise.getValue()).getInstant().atZone(ZoneId.systemDefault());
        ZonedDateTime set = ((DateTimeType) sunset.getValue()).getInstant().atZone(ZoneId.systemDefault());
        assertEquals(6, rise.getHour());
        assertEquals(30, rise.getMinute());
        assertEquals(19, set.getHour());
        assertEquals(45, set.getMinute());
    }

    @Test
    public void sunriseSunsetNotConfiguredIsIgnored() throws Exception {
        loggedIn();

        respond("~:ra 255 255 255 255 1 OK");
        respond("~:rs 2 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SCHEDULE, new DecimalType(2));
        verify(callback, never()).stateChanged(eq(PrgConstants.CHANNEL_SUNRISE), any());
    }

    @Test
    public void superSequenceStatus() throws Exception {
        loggedIn();

        respond("~:s? R 3 12 30 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SUPERSEQUENCENEXTSEC, new DecimalType(30));
        verify(callback).stateChanged(PrgConstants.CHANNEL_SUPERSEQUENCESTATUS, new StringType("R"));
        verify(callback).stateChanged(PrgConstants.CHANNEL_SUPERSEQUENCENEXTSTEP, new DecimalType(3));
        verify(callback).stateChanged(PrgConstants.CHANNEL_SUPERSEQUENCENEXTMIN, new DecimalType(12));
    }

    @Test
    public void buttonPressIsReportedVerbatim() throws Exception {
        loggedIn();

        respond("1 2 3 button");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_BUTTONPRESS,
                new StringType("1 2 3 button"));
    }

    @Test
    public void resettingTakesTheBridgeOffline() throws Exception {
        loggedIn();

        respond("~:Reseting Device... 1 OK");

        verify(callback, timeout(WAIT_MS)).statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE,
                "Device resetting");
    }

    @Test
    public void okErrorsAndUnknownResponsesChangeNothing() throws Exception {
        loggedIn();

        respond("~1 OK");
        respond("~ERROR # 6 1 OK");
        respond("~ERROR # 999 1 OK");
        respond("~:xx 1 2 3");
        respond(":zz");
        respond("   ");
        respond("~:rs 0 1 OK");

        verify(callback, timeout(WAIT_MS)).stateChanged(PrgConstants.CHANNEL_SCHEDULE, new DecimalType(0));
        verify(callback, never()).statusChanged(any(), any(), any());
        verify(callback, times(1)).stateChanged(eq(PrgConstants.CHANNEL_SCHEDULE), any());
        verify(callback, never()).stateChanged(eq(PrgConstants.CHANNEL_BUTTONPRESS), any());
        verify(callback, never()).stateChanged(anyInt(), anyString(), any());
    }

    @Test
    public void closedConnectionTakesTheBridgeOffline() throws Exception {
        loggedIn();

        prg.close();

        verify(callback, timeout(WAIT_MS)).statusChanged(eq(ThingStatus.OFFLINE),
                eq(ThingStatusDetail.COMMUNICATION_ERROR), contains("reading from PRG"));
    }

    @Test
    public void controlCommands() throws Exception {
        loggedIn();

        handler.selectScene(1, 5);
        handler.selectScene(2, 16);
        handler.setSceneLock(2, true);
        handler.setSceneSequence(3, false);
        handler.setZoneLock(4, true);
        handler.setZoneLower(1, 2);
        handler.setZoneLowerStop();
        handler.setZoneRaise(1, 2);
        handler.setZoneRaiseStop();
        handler.refreshZoneIntensity(2);
        handler.selectSchedule(1);
        handler.refreshTime();
        handler.refreshSchedule();
        handler.refreshSunriseSunset();
        handler.startSuperSequence();
        handler.pauseSuperSequence();
        handler.resumeSuperSequence();

        List<String> expected = List.of("A51", "AG2", "SL+2", "SQ-3", "ZL+4", "D12", "E", "B12", "C", "rzi 2", "SS 1",
                "RT", "RS", "RA", "QS", "Q?", "QP", "QC");
        for (String command : expected) {
            assertEquals(command, nextCommand());
        }
    }

    @Test
    public void refreshStateQueriesEverything() throws Exception {
        loggedIn();

        handler.refreshState(1);

        assertEquals(List.of("G", "RT", "RS", "RA", "Q?"),
                List.of(nextCommand(), nextCommand(), nextCommand(), nextCommand(), nextCommand()));
    }

    @Test
    public void zoneIntensityCommand() throws Exception {
        loggedIn();

        handler.setZoneIntensity(1, 3, 5, 50);
        handler.setZoneIntensity(2, 1, 75, 100);

        assertEquals("szi 1 5 * * 40 * * * * *", nextCommand());
        // Fades of a minute or more are sent in minutes, offset by 59
        assertEquals("szi 2 3C 7F * * * * * * *", nextCommand());
    }

    @Test
    public void shadeIntensityCommand() throws Exception {
        when(callback.isShade(1, 2)).thenReturn(true);
        loggedIn();

        handler.setZoneIntensity(1, 2, 0, 3);

        assertEquals("szi 1 0 * 3 * * * * * *", nextCommand());
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneIntensity(1, 2, 0, 6));
    }

    @Test
    public void commandArgumentsAreValidated() throws Exception {
        loggedIn();

        assertThrows(IllegalArgumentException.class, () -> handler.selectScene(0, 1));
        assertThrows(IllegalArgumentException.class, () -> handler.selectScene(9, 1));
        assertThrows(IllegalArgumentException.class, () -> handler.selectScene(1, -1));
        assertThrows(IllegalArgumentException.class, () -> handler.selectScene(1, 17));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneLower(1, 0));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneRaise(1, 9));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneIntensity(1, 1, -1, 50));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneIntensity(1, 1, 121, 50));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneIntensity(1, 1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> handler.setZoneIntensity(1, 1, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> handler.selectSchedule(3));
        assertThrows(IllegalArgumentException.class, () -> handler.refreshZoneIntensity(0));

        handler.refreshTime();
        assertEquals("RT", nextCommand(), "an invalid command must not send anything");
    }

    @Test
    public void sendingWhileDisconnectedTakesTheBridgeOffline() throws Exception {
        loggedIn();
        session.disconnect();

        handler.refreshTime();

        verify(callback).statusChanged(eq(ThingStatus.OFFLINE), eq(ThingStatusDetail.COMMUNICATION_ERROR),
                contains("disconnected"));
    }

    @Test
    public void loginTimeoutIsBounded() {
        // A missing login prompt must not block the caller beyond the five second response timeout
        assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS),
                () -> assertThrows(Exception.class, () -> handler.login("lutron")));
    }
}
