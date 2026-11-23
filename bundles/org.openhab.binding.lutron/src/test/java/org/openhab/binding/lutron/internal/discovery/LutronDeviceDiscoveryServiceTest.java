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
package org.openhab.binding.lutron.internal.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.lutron.internal.LutronBindingConstants.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.lutron.internal.config.IPBridgeConfig;
import org.openhab.binding.lutron.internal.handler.IPBridgeHandler;
import org.openhab.core.config.discovery.DiscoveryListener;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.ScanListener;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;

/**
 * Tests for {@link LutronDeviceDiscoveryService} reading a project export from a file.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class LutronDeviceDiscoveryServiceTest {
    private static final long WAIT_MS = 20_000;
    private static final int SAMPLE_RESULTS = 13;

    private final ThingUID bridgeUid = new ThingUID("lutron:ipbridge:home");
    private final IPBridgeHandler bridgeHandler = mock(IPBridgeHandler.class);
    private final DiscoveryListener listener = mock(DiscoveryListener.class);
    private final IPBridgeConfig config = new IPBridgeConfig();
    private @NonNullByDefault({}) LutronDeviceDiscoveryService service;
    private @TempDir @NonNullByDefault({}) Path dir;

    @BeforeEach
    public void setUp() {
        Bridge bridge = mock(Bridge.class);
        when(bridge.getUID()).thenReturn(bridgeUid);
        when(bridgeHandler.getThing()).thenReturn(bridge);
        when(bridgeHandler.getIPBridgeConfig()).thenReturn(config);
        config.ipAddress = "127.0.0.1";
        service = new LutronDeviceDiscoveryService(bridgeHandler, mock(HttpClient.class));
        service.addDiscoveryListener(listener);
    }

    @AfterEach
    public void tearDown() {
        service.removeDiscoveryListener(listener);
    }

    private static String sample() throws Exception {
        try (InputStream in = Objects
                .requireNonNull(LutronDeviceDiscoveryServiceTest.class.getResourceAsStream("/DbXmlInfo-sample.xml"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void scan(String fileContent) throws Exception {
        Path file = dir.resolve("DbXmlInfo.xml");
        Files.writeString(file, fileContent, StandardCharsets.UTF_8);
        config.discoveryFile = file.toString();
        service.startScan(null);
    }

    private Map<String, DiscoveryResult> awaitResults(int count) {
        ArgumentCaptor<DiscoveryResult> captor = ArgumentCaptor.forClass(DiscoveryResult.class);
        verify(listener, timeout(WAIT_MS).times(count)).thingDiscovered(eq(service), captor.capture());
        return captor.getAllValues().stream()
                .collect(Collectors.toMap(r -> r.getThingUID().getId(), Function.identity()));
    }

    private static DiscoveryResult result(Map<String, DiscoveryResult> results, String id) {
        DiscoveryResult result = results.get(id);
        assertNotNull(result, "nothing discovered with integration ID " + id);
        return result;
    }

    private static void assertResult(DiscoveryResult result, ThingTypeUID type, String label) {
        assertEquals(type, result.getThingTypeUID());
        assertEquals(label, result.getLabel().trim());
        assertEquals(INTEGRATION_ID, result.getRepresentationProperty());
        assertEquals(Integer.valueOf(result.getThingUID().getId()), result.getProperties().get(INTEGRATION_ID));
    }

    @Test
    public void discoversEverySupportedDeviceAndOutput() throws Exception {
        scan(sample());

        Map<String, DiscoveryResult> results = awaitResults(SAMPLE_RESULTS);

        assertResult(result(results, "1"), THING_TYPE_VIRTUALKEYPAD, "Home Main Repeater");
        assertResult(result(results, "20"), THING_TYPE_KEYPAD, "Home Kitchen Entry Keypad");
        assertEquals("W4S", result(results, "20").getProperties().get("model"));
        assertResult(result(results, "21"), THING_TYPE_OCCUPANCYSENSOR, "Home Kitchen Ceiling Sensor");
        assertResult(result(results, "10"), THING_TYPE_OGROUP, "Kitchen");
        assertResult(result(results, "30"), THING_TYPE_DIMMER, "Home Kitchen Pendants");
        assertResult(result(results, "31"), THING_TYPE_SWITCH, "Home Kitchen Pantry");
        assertResult(result(results, "32"), THING_TYPE_FAN, "Home Kitchen Ceiling Fan");
        assertResult(result(results, "33"), THING_TYPE_CCO, "Home Kitchen Gate");
        assertEquals(CCO_TYPE_PULSED, result(results, "33").getProperties().get(CCO_TYPE));
        assertResult(result(results, "34"), THING_TYPE_CCO, "Home Kitchen Pump");
        assertEquals(CCO_TYPE_MAINTAINED, result(results, "34").getProperties().get(CCO_TYPE));
        assertResult(result(results, "35"), THING_TYPE_SHADE, "Home Kitchen Window");
        assertResult(result(results, "36"), THING_TYPE_BLIND, "Home Kitchen Blind");
        assertEquals(BLIND_TYPE_VENETIAN, result(results, "36").getProperties().get(BLIND_TYPE_PARAMETER));
        assertResult(result(results, "40"), THING_TYPE_TIMECLOCK, "Schedule");
        assertResult(result(results, "41"), THING_TYPE_GREENMODE, "Eco");

        for (DiscoveryResult result : results.values()) {
            assertEquals(bridgeUid, result.getBridgeUID());
        }
        assertFalse(results.containsKey("22"), "unknown device type");
        assertFalse(results.containsKey("37"), "unknown output type");
    }

    @Test
    public void linesBeforeTheXmlDeclarationAreSkipped() throws Exception {
        scan("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n  " + sample());

        awaitResults(SAMPLE_RESULTS);
    }

    @Test
    public void textBeforeTheDeclarationOnTheSameLineIsSkipped() throws Exception {
        scan("junk<?xml version=\"1.0\"?>\n<Project><GreenModes><GreenMode Name=\"Eco\" IntegrationID=\"41\"/>"
                + "</GreenModes></Project>");

        assertResult(result(awaitResults(1), "41"), THING_TYPE_GREENMODE, "Eco");
    }

    @Test
    public void missingFileFindsNothing() {
        config.discoveryFile = dir.resolve("missing.xml").toString();
        ScanListener scanListener = mock(ScanListener.class);

        assertDoesNotThrow(() -> service.startScan(scanListener));

        verify(listener, after(1_000).never()).thingDiscovered(any(), any());
        verify(scanListener, never()).onErrorOccurred(any());
    }

    @Test
    public void malformedXmlFindsNothingAndReportsAnError() throws Exception {
        Path file = dir.resolve("DbXmlInfo.xml");
        Files.writeString(file, "<?xml version=\"1.0\"?>\n<Project><Areas><Area Name=\"A\" IntegrationID=\"zz\"/>");
        config.discoveryFile = file.toString();
        ScanListener scanListener = mock(ScanListener.class);

        service.startScan(scanListener);

        verify(scanListener, timeout(WAIT_MS)).onErrorOccurred(null);
        verify(listener, never()).thingDiscovered(any(), any());
    }

    @Test
    public void fileWithoutAnXmlDeclarationFindsNothing() throws Exception {
        ScanListener scanListener = mock(ScanListener.class);
        Path file = dir.resolve("DbXmlInfo.xml");
        Files.writeString(file, "this is not a project export\nat all\n");
        config.discoveryFile = file.toString();

        service.startScan(scanListener);

        verify(scanListener, timeout(WAIT_MS)).onErrorOccurred(null);
        verify(listener, never()).thingDiscovered(any(), any());
    }

    @Test
    public void externalEntitiesAreNotResolved() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET");
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Project [<!ENTITY xxe SYSTEM "%s">]>
                <Project><GreenModes><GreenMode Name="&xxe;" IntegrationID="41"/></GreenModes></Project>
                """.formatted(secret.toUri());
        ScanListener scanListener = mock(ScanListener.class);
        Path file = dir.resolve("DbXmlInfo.xml");
        Files.writeString(file, xml);
        config.discoveryFile = file.toString();

        service.startScan(scanListener);

        // Either the document is rejected or the entity stays unresolved; the file content must never leak
        verify(listener, after(2_000).atMost(1)).thingDiscovered(any(), any());
        ArgumentCaptor<DiscoveryResult> captor = ArgumentCaptor.forClass(DiscoveryResult.class);
        verify(listener, atMost(1)).thingDiscovered(any(), captor.capture());
        List<DiscoveryResult> results = captor.getAllValues();
        for (DiscoveryResult result : results) {
            assertFalse(result.getLabel().contains("TOPSECRET"), "external entity was resolved");
        }
    }
}
