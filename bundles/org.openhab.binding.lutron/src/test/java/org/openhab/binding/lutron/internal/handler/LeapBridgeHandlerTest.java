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
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.lutron.internal.LutronBindingConstants;
import org.openhab.binding.lutron.internal.protocol.leap.LeapMessageParser;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.Command;

/**
 * Tests for {@link LeapBridgeHandler}. Bridge messages are fed through a {@link LeapMessageParser}, exactly as the
 * reader thread does, without a TLS connection.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class LeapBridgeHandlerTest {
    private static final long WAIT_MS = 20_000;

    private static final String STATUS_INITIALIZING = "Initializing";

    private static final String DEVICES = """
            {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleDeviceDefinition","Url":"/device"},\
            "Body":{"Devices":[\
            {"href":"/device/1","Name":"Smart Bridge 2","ModelNumber":"L-BDG2-WH","SerialNumber":"12345678",\
            "RepeaterProperties":{"IsRepeater":true},"FirmwareImage":{"Firmware":{"DisplayName":"08.25.17f000"}}},\
            {"href":"/device/2","Name":"Dimmer","LocalZones":[{"href":"/zone/1"}]},\
            {"href":"/device/3","Name":"Pico"},\
            {"href":"/device/4","Name":"Fan","LocalZones":[{"href":"/zone/2"}]}]}}""";

    private static final String BUTTON_GROUPS = """
            {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleButtonGroupDefinition",\
            "Url":"/buttongroup"},"Body":{"ButtonGroups":[{"href":"/buttongroup/2","Parent":{"href":"/device/3"},\
            "Buttons":[{"href":"/button/101"},{"href":"/button/102"},{"href":"/button/103"}]}]}}""";

    private static final String NO_BUTTON_GROUPS = """
            {"CommuniqueType":"ReadResponse","Header":{"StatusCode":"204 NoContent","Url":"/buttongroup"}}""";

    /**
     * A minimal child handler that records the updates it is given. Safe to call from several threads.
     */
    private static class RecordingChild extends LutronHandler {
        private final int integrationId;
        final Queue<List<String>> updates = new ConcurrentLinkedQueue<>();

        RecordingChild(int integrationId) {
            super(mock(Thing.class));
            this.integrationId = integrationId;
        }

        @Override
        public int getIntegrationId() {
            return integrationId;
        }

        @Override
        public void handleUpdate(LutronCommandType type, String... parameters) {
            List<String> update = new ArrayList<>();
            update.add(type.toString());
            update.addAll(List.of(parameters));
            updates.add(update);
        }

        @Override
        protected void initDeviceState() {
        }

        @Override
        public void initialize() {
        }

        @Override
        public void handleCommand(ChannelUID channelUID, Command command) {
        }
    }

    private final Bridge bridge = mock(Bridge.class);
    private final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    private @NonNullByDefault({}) LeapBridgeHandler handler;
    private @NonNullByDefault({}) LeapMessageParser parser;

    @BeforeEach
    public void setUp() {
        when(bridge.getUID()).thenReturn(new ThingUID("lutron:leapbridge:test"));
        setBridgeStatus(ThingStatus.OFFLINE, STATUS_INITIALIZING);
        handler = new LeapBridgeHandler(bridge);
        handler.setCallback(callback);
        parser = new LeapMessageParser(handler);
    }

    @AfterEach
    public void tearDown() {
        handler.dispose();
    }

    private void setBridgeStatus(ThingStatus status, @Nullable String description) {
        when(bridge.getStatusInfo()).thenReturn(new ThingStatusInfo(status, ThingStatusDetail.NONE, description));
    }

    private RecordingChild register(int integrationId) {
        RecordingChild child = new RecordingChild(integrationId);
        handler.childHandlerInitialized(child, mock(Thing.class));
        return child;
    }

    private static String zoneStatus(int zone, int level) {
        return """
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneZoneStatus"},\
                "Body":{"ZoneStatus":{"Level":%d,"Zone":{"href":"/zone/%d"}}}}""".formatted(level, zone);
    }

    private static String buttonEvent(int button, String eventType) {
        return """
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneButtonStatusEvent"},\
                "Body":{"ButtonStatus":{"Button":{"href":"/button/%d"},"ButtonEvent":{"EventType":"%s"}}}}"""
                .formatted(button, eventType);
    }

    private static String occupancy(int group, String status) {
        return """
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleOccupancyGroupStatus"},\
                "Body":{"OccupancyGroupStatuses":[\
                {"OccupancyGroup":{"href":"/occupancygroup/%d"},"OccupancyStatus":"%s"}]}}""".formatted(group, status);
    }

    private void verifyWentOnline() {
        verify(callback).statusUpdated(eq(bridge), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    private void verifyNeverOnline() {
        verify(callback, never()).statusUpdated(any(), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    @Test
    public void goesOnlineOnceDevicesAndButtonsAreLoaded() {
        parser.handleMessage(DEVICES);
        verifyNeverOnline();

        parser.handleMessage(BUTTON_GROUPS);
        verifyWentOnline();
    }

    @Test
    public void goesOnlineWhenTheHubHasNoButtons() {
        parser.handleMessage(NO_BUTTON_GROUPS);
        parser.handleMessage(DEVICES);
        verifyWentOnline();
    }

    @Test
    public void staysOfflineWhileStillConnecting() {
        setBridgeStatus(ThingStatus.OFFLINE, "Connecting");
        parser.handleMessage(DEVICES);
        parser.handleMessage(BUTTON_GROUPS);
        verifyNeverOnline();
    }

    @Test
    public void hubDeviceSetsBridgeProperties() {
        parser.handleMessage(DEVICES);

        verify(bridge).setProperty(Thing.PROPERTY_MODEL_ID, "L-BDG2-WH");
        verify(bridge).setProperty(Thing.PROPERTY_SERIAL_NUMBER, "12345678");
        verify(bridge).setProperty(Thing.PROPERTY_FIRMWARE_VERSION, "08.25.17f000");
        verify(bridge).setProperty(LutronBindingConstants.PROPERTY_PRODTYP, "Smart Bridge 2");
    }

    @Test
    public void thisDeviceSetsBridgeProperties() {
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneDeviceDefinition"},\
                "Body":{"Device":{"href":"/device/7","Name":"RA3 Processor","ModelNumber":"JanusProcRA3",\
                "IsThisDevice":true}}}""");

        verify(bridge).setProperty(Thing.PROPERTY_MODEL_ID, "JanusProcRA3");
    }

    @Test
    public void zoneUpdateReachesTheDeviceOwningTheZone() {
        RecordingChild dimmer = register(2);
        parser.handleMessage(DEVICES);

        parser.handleMessage(zoneStatus(1, 42));

        assertEquals(List.of(List.of("OUTPUT", "1", "42")), List.copyOf(dimmer.updates));
    }

    @Test
    public void fanZoneUpdateIsSentAsPercentage() {
        RecordingChild fan = register(4);
        parser.handleMessage(DEVICES);

        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneZoneStatus"},\
                "Body":{"ZoneStatus":{"FanSpeed":"MediumHigh","Zone":{"href":"/zone/2"}}}}""");

        assertEquals(List.of(List.of("OUTPUT", "1", "75")), List.copyOf(fan.updates));
    }

    @Test
    public void zoneUpdatesForUnknownZonesOrUnconfiguredThingsAreDropped() {
        RecordingChild dimmer = register(2);
        parser.handleMessage(DEVICES);

        parser.handleMessage(zoneStatus(99, 10));
        parser.handleMessage(zoneStatus(2, 10)); // zone 2 belongs to device 4, which has no thing

        assertTrue(dimmer.updates.isEmpty());
    }

    @Test
    public void zoneUpdateBeforeDeviceDataIsDropped() {
        RecordingChild dimmer = register(2);
        parser.handleMessage(zoneStatus(1, 42));
        assertTrue(dimmer.updates.isEmpty());
    }

    @Test
    public void disposedChildNoLongerReceivesUpdates() {
        RecordingChild dimmer = register(2);
        parser.handleMessage(DEVICES);
        handler.childHandlerDisposed(dimmer, mock(Thing.class));

        parser.handleMessage(zoneStatus(1, 42));

        assertTrue(dimmer.updates.isEmpty());
    }

    @Test
    public void newDeviceListReplacesTheZoneMap() {
        RecordingChild dimmer = register(2);
        RecordingChild other = register(5);
        parser.handleMessage(DEVICES);
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleDeviceDefinition"},\
                "Body":{"Devices":[{"href":"/device/5","LocalZones":[{"href":"/zone/1"}]}]}}""");

        parser.handleMessage(zoneStatus(1, 7));

        assertTrue(dimmer.updates.isEmpty());
        assertEquals(List.of(List.of("OUTPUT", "1", "7")), List.copyOf(other.updates));
    }

    @Test
    public void childThatThrowsDoesNotBreakDispatch() {
        LutronHandler failing = mock(LutronHandler.class);
        when(failing.getIntegrationId()).thenReturn(2);
        doThrow(new IllegalArgumentException("bad level")).when(failing).handleUpdate(any(), any(String[].class));
        handler.childHandlerInitialized(failing, mock(Thing.class));
        RecordingChild fan = register(4);
        parser.handleMessage(DEVICES);

        assertDoesNotThrow(() -> parser.handleMessage(zoneStatus(1, 150)));
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneZoneStatus"},\
                "Body":{"ZoneStatus":{"FanSpeed":"Low","Zone":{"href":"/zone/2"}}}}""");

        assertEquals(1, fan.updates.size());
    }

    @Test
    public void buttonEventsMapToKeypadComponentActions() {
        RecordingChild pico = register(3);
        parser.handleMessage(DEVICES);
        parser.handleMessage(BUTTON_GROUPS);

        parser.handleMessage(buttonEvent(102, "Press"));
        parser.handleMessage(buttonEvent(102, "Release"));
        parser.handleMessage(buttonEvent(101, "LongHold"));
        parser.handleMessage(buttonEvent(103, "MultiTap"));

        assertEquals(List.of(List.of("DEVICE", "2", "3"), List.of("DEVICE", "2", "4"), List.of("DEVICE", "1", "5"),
                List.of("DEVICE", "3", "6")), List.copyOf(pico.updates));
    }

    @Test
    public void unknownButtonsAndEventTypesAreDropped() {
        RecordingChild pico = register(3);
        parser.handleMessage(BUTTON_GROUPS);

        parser.handleMessage(buttonEvent(999, "Press"));
        parser.handleMessage(buttonEvent(101, "TripleTap"));
        parser.handleMessage(buttonEvent(101, ""));

        assertTrue(pico.updates.isEmpty());
    }

    @Test
    public void buttonEventBeforeButtonDataIsDropped() {
        RecordingChild pico = register(3);
        parser.handleMessage(buttonEvent(101, "Press"));
        assertTrue(pico.updates.isEmpty());
    }

    @Test
    public void emptyButtonGroupResponseForgetsEarlierButtons() {
        RecordingChild pico = register(3);
        parser.handleMessage(BUTTON_GROUPS);
        parser.handleMessage(NO_BUTTON_GROUPS);

        parser.handleMessage(buttonEvent(101, "Press"));

        assertTrue(pico.updates.isEmpty());
        assertEquals(0, handler.getButton(3, 1));
    }

    @Test
    public void getButtonMapsComponentIndexToLeapButton() {
        assertEquals(0, handler.getButton(3, 1), "no button data loaded yet");
        parser.handleMessage(BUTTON_GROUPS);

        assertEquals(101, handler.getButton(3, 1));
        assertEquals(103, handler.getButton(3, 3));
        assertEquals(0, handler.getButton(3, 4));
        assertEquals(0, handler.getButton(99, 1));
    }

    @Test
    public void componentNumbersBelowOneHaveNoButton() {
        parser.handleMessage(BUTTON_GROUPS);

        assertEquals(0, handler.getButton(3, 0));
        assertEquals(0, handler.getButton(3, -1));
    }

    @Test
    public void incompleteDefinitionsAndEventsAreTolerated() {
        RecordingChild pico = register(3);

        assertDoesNotThrow(() -> parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleButtonGroupDefinition"},\
                "Body":{"ButtonGroups":[{"href":"/buttongroup/1","Parent":{"href":"/device/9"}},\
                {"href":"/buttongroup/2","Parent":{"href":"/device/3"},"Buttons":[null,{"href":"/button/101"}]}]}}"""));
        assertDoesNotThrow(() -> parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneButtonStatusEvent"},\
                "Body":{"ButtonStatus":{"Button":{"href":"/button/101"}}}}"""));
        assertDoesNotThrow(() -> parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"MultipleZoneExpandedStatus"},\
                "Body":{"ZoneExpandedStatuses":[{"href":"/zone/1/status","Level":0}]}}"""));
        assertDoesNotThrow(() -> parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneProjectDefinition"},\
                "Body":{"Project":{"ProductType":"Lutron Smart Bridge Project"}}}"""));
        assertDoesNotThrow(() -> parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneProjectDefinition"},\
                "Body":{"Project":{"MasterDeviceList":{"Devices":[null]}}}}"""));

        assertEquals(101, handler.getButton(3, 1));
        assertTrue(pico.updates.isEmpty());
        parser.handleMessage(buttonEvent(101, "Press"));
        assertEquals(List.of(List.of("DEVICE", "1", "3")), List.copyOf(pico.updates));
    }

    @Test
    public void occupancyStatusReachesTheGroupHandler() {
        OGroupHandler group = mock(OGroupHandler.class);
        when(group.getIntegrationId()).thenReturn(5);
        handler.childHandlerInitialized(group, mock(Thing.class));

        parser.handleMessage(occupancy(5, "Occupied"));
        parser.handleMessage(occupancy(5, "Unoccupied"));
        parser.handleMessage(occupancy(5, "Unknown"));
        parser.handleMessage(occupancy(5, "Sleepy"));
        parser.handleMessage(occupancy(6, "Occupied"));

        verify(group).handleUpdate(LutronCommandType.GROUP, "3", "3");
        verify(group).handleUpdate(LutronCommandType.GROUP, "3", "4");
        verify(group).handleUpdate(LutronCommandType.GROUP, "3", "255");
        verify(group, times(3)).handleUpdate(any(), any(String[].class));
    }

    @Test
    public void groupAndDeviceNumbersAreSeparate() {
        OGroupHandler group = mock(OGroupHandler.class);
        when(group.getIntegrationId()).thenReturn(2);
        handler.childHandlerInitialized(group, mock(Thing.class));
        RecordingChild dimmer = register(2);
        parser.handleMessage(DEVICES);

        parser.handleMessage(zoneStatus(1, 50));
        parser.handleMessage(occupancy(2, "Occupied"));

        assertEquals(1, dimmer.updates.size());
        verify(group).handleUpdate(LutronCommandType.GROUP, "3", "3");
    }

    @Test
    public void concurrentMapUpdatesDispatchAndChildRegistration() throws Exception {
        final int threads = 4;
        final int rounds = 500;
        CyclicBarrier start = new CyclicBarrier(threads);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        RecordingChild dimmer = register(2);
        RecordingChild pico = register(3);

        Runnable[] jobs = { () -> {
            for (int i = 0; i < rounds; i++) {
                parser.handleMessage(DEVICES);
            }
        }, () -> {
            for (int i = 0; i < rounds; i++) {
                parser.handleMessage(zoneStatus(1, i % 101));
            }
        }, () -> {
            for (int i = 0; i < rounds; i++) {
                parser.handleMessage(i % 2 == 0 ? BUTTON_GROUPS : NO_BUTTON_GROUPS);
                parser.handleMessage(buttonEvent(101, "Press"));
                handler.getButton(3, 1);
            }
        }, () -> {
            for (int i = 0; i < rounds; i++) {
                RecordingChild transientChild = new RecordingChild(100 + i % 10);
                handler.childHandlerInitialized(transientChild, mock(Thing.class));
                handler.childHandlerDisposed(transientChild, mock(Thing.class));
            }
        } };

        List<Thread> workers = new ArrayList<>();
        for (Runnable job : jobs) {
            Thread worker = new Thread(() -> {
                try {
                    start.await(WAIT_MS, TimeUnit.MILLISECONDS);
                    job.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }, "leap-concurrency-test");
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(WAIT_MS);
            assertFalse(worker.isAlive(), "worker did not finish");
        }

        assertTrue(failures.isEmpty(), () -> "worker failed: " + failures);

        // After the dust settles the maps are consistent and dispatch still works
        dimmer.updates.clear();
        pico.updates.clear();
        parser.handleMessage(BUTTON_GROUPS);
        parser.handleMessage(zoneStatus(1, 33));
        parser.handleMessage(buttonEvent(103, "Press"));
        assertEquals(List.of(List.of("OUTPUT", "1", "33")), List.copyOf(dimmer.updates));
        assertEquals(List.of(List.of("DEVICE", "3", "3")), List.copyOf(pico.updates));
    }

    private void initializeWith(Map<String, Object> config) {
        when(bridge.getConfiguration()).thenReturn(new Configuration(config));
        handler.initialize();
    }

    private void verifyStatus(ThingStatus status, ThingStatusDetail detail) {
        verify(callback, timeout(WAIT_MS)).statusUpdated(eq(bridge),
                argThat(info -> info.getStatus() == status && info.getStatusDetail() == detail));
    }

    @Test
    public void missingAddressIsAConfigurationError() {
        initializeWith(Map.of());
        verifyStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
    }

    @Test
    public void missingKeystoreIsAConfigurationError() {
        initializeWith(Map.of("ipAddress", "127.0.0.1"));
        verifyStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
    }

    @Test
    public void keystoreFileNotFoundIsAConfigurationError(@TempDir Path dir) {
        initializeWith(Map.of("ipAddress", "127.0.0.1", "keystore", dir.resolve("missing.p12").toString()));
        verify(callback).statusUpdated(eq(bridge),
                argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                        && info.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR
                        && "Keystore file not found".equals(info.getDescription())));
    }

    @Test
    public void corruptKeystoreKeepsTheBridgeOffline(@TempDir Path dir) throws IOException {
        Path keystore = dir.resolve("garbage.p12");
        Files.write(keystore, new byte[] { 0x30, 0x03, 0x02, 0x01, 0x00, 0x7f, 0x7f, 0x7f });
        Map<String, Object> config = new HashMap<>();
        config.put("ipAddress", "127.0.0.1");
        config.put("keystore", keystore.toString());
        config.put("keystorePassword", "secret");
        initializeWith(config);

        verify(callback).statusUpdated(eq(bridge), argThat(info -> info.getStatus() == ThingStatus.OFFLINE));
        verify(callback, never()).statusUpdated(any(), argThat(info -> "Connecting".equals(info.getDescription())));
    }

    private static Path emptyKeystore(Path dir) throws Exception {
        Path keystore = dir.resolve("empty.p12");
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        try (OutputStream out = Files.newOutputStream(keystore)) {
            empty.store(out, "secret".toCharArray());
        }
        return keystore;
    }

    private void initializeAgainst(ServerSocket peer, Path keystore) {
        initializeWith(Map.of("ipAddress", "127.0.0.1", "port", peer.getLocalPort(), "keystore", keystore.toString(),
                "keystorePassword", "secret"));
    }

    @Test
    public void peerThatNeverStartsTlsIsACommunicationErrorAndIsRetried(@TempDir Path dir) throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            handler.handshakeTimeoutMs = 500;
            initializeAgainst(silent, emptyKeystore(dir));

            verify(callback, timeout(WAIT_MS)).statusUpdated(eq(bridge),
                    argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                            && info.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR));
        }
    }

    @Test
    public void disposeDuringTheTlsHandshakeReturnsPromptly(@TempDir Path dir) throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            silent.setSoTimeout((int) WAIT_MS);
            initializeAgainst(silent, emptyKeystore(dir));
            try (Socket accepted = silent.accept()) {
                Thread.sleep(300); // let the handshake start waiting
                clearInvocations(callback);

                assertTimeoutPreemptively(Duration.ofSeconds(5), handler::dispose);

                verify(callback, after(1_000).never()).statusUpdated(any(),
                        argThat(info -> info.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR));
            }
        }
    }

    @Test
    public void unreachableBridgeIsACommunicationErrorAndIsRetried(@TempDir Path dir) throws Exception {
        Path keystore = emptyKeystore(dir);
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        initializeWith(Map.of("ipAddress", "127.0.0.1", "port", closedPort, "keystore", keystore.toString(),
                "keystorePassword", "secret"));

        verify(callback, timeout(WAIT_MS)).statusUpdated(eq(bridge),
                argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                        && info.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR));
        assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS), handler::dispose);
    }
}
