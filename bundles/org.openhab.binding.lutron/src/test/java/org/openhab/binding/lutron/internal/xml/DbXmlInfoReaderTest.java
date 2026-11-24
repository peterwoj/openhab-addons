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
package org.openhab.binding.lutron.internal.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.lutron.internal.discovery.project.Area;
import org.openhab.binding.lutron.internal.discovery.project.ComponentType;
import org.openhab.binding.lutron.internal.discovery.project.Device;
import org.openhab.binding.lutron.internal.discovery.project.DeviceGroup;
import org.openhab.binding.lutron.internal.discovery.project.DeviceType;
import org.openhab.binding.lutron.internal.discovery.project.Output;
import org.openhab.binding.lutron.internal.discovery.project.OutputType;
import org.openhab.binding.lutron.internal.discovery.project.Project;

import com.thoughtworks.xstream.XStreamException;

/**
 * Tests for {@link DbXmlInfoReader}, which reads the DbXmlInfo.xml project export of a Lutron main repeater.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class DbXmlInfoReaderTest {
    private final DbXmlInfoReader reader = new DbXmlInfoReader();

    private Project readSample() throws Exception {
        URL sample = Objects.requireNonNull(getClass().getResource("/DbXmlInfo-sample.xml"));
        try (Reader in = new InputStreamReader(sample.openStream(), StandardCharsets.UTF_8)) {
            Project project = reader.readFromXML(in);
            assertNotNull(project);
            return project;
        }
    }

    private static @Nullable Project read(DbXmlInfoReader reader, String xml) {
        return reader.readFromXML(new StringReader(xml));
    }

    @Test
    public void readsTheProjectHierarchy() throws Exception {
        Project project = readSample();

        assertEquals("12.5.0.8", project.getAppVersion());
        assertEquals("3.0", project.getXmlVersion());
        assertEquals(1, project.getAreas().size());

        Area home = project.getAreas().get(0);
        assertEquals("Home", home.getName());
        Device repeater = (Device) home.getDeviceNodes().get(0);
        assertEquals(DeviceType.MAIN_REPEATER, repeater.getDeviceType());
        assertEquals(1, repeater.getIntegrationId());

        Area kitchen = home.getAreas().get(0);
        assertEquals("Kitchen", kitchen.getName());
        assertEquals(10, kitchen.getIntegrationId());
        DeviceGroup entry = (DeviceGroup) kitchen.getDeviceNodes().get(0);
        assertEquals("Entry", entry.getName());
        Device keypad = entry.getDevices().get(0);
        assertEquals(DeviceType.SEETOUCH_KEYPAD, keypad.getDeviceType());
        assertEquals(9, keypad.getComponents().size());
        assertEquals(ComponentType.LED, keypad.getComponents().get(8).getComponentType());
        assertEquals(82, keypad.getComponents().get(8).getComponentNumber());

        Output pendants = kitchen.getOutputs().get(0);
        assertEquals("Pendants", pendants.getName());
        assertEquals(OutputType.INC, pendants.getOutputType());

        assertEquals(40, project.getTimeclocks().get(0).getIntegrationId());
        assertEquals("Eco", project.getGreenModes().get(0).getName());
    }

    @Test
    public void unknownTypesAreKeptAsUnrecognized() throws Exception {
        Area kitchen = readSample().getAreas().get(0).getAreas().get(0);

        Device gadget = ((DeviceGroup) kitchen.getDeviceNodes().get(0)).getDevices().get(1);
        assertEquals("FLUX_CAPACITOR", gadget.getType());
        assertNull(gadget.getDeviceType());

        List<Output> outputs = kitchen.getOutputs();
        Output mystery = outputs.get(7);
        assertEquals("PLASMA", mystery.getType());
        assertNull(mystery.getOutputType());
        assertNull(outputs.get(8).getIntegrationId());
    }

    @Test
    public void emptyProjectHasEmptyLists() {
        Project project = read(reader, "<Project/>");
        assertNotNull(project);
        assertTrue(project.getAreas().isEmpty());
        assertTrue(project.getTimeclocks().isEmpty());
        assertTrue(project.getGreenModes().isEmpty());
    }

    @Test
    public void elementsFromNewerSchemasAreIgnored() {
        Project project = read(reader, """
                <Project><FutureThing a="1"><Nested/></FutureThing>\
                <Areas><Area Name="A" IntegrationID="3" Color="blue"><Lighting/></Area></Areas></Project>""");
        assertNotNull(project);
        assertEquals("A", project.getAreas().get(0).getName());
    }

    @Test
    public void nullSourceGivesNoProject() {
        assertNull(reader.readFromXML((Reader) null));
        assertNull(reader.readFromXML((URL) null));
    }

    @Test
    public void malformedXmlIsRejected() {
        assertThrows(XStreamException.class, () -> read(reader, "<Project><Areas><Area Name=\"A\">"));
        assertThrows(XStreamException.class, () -> read(reader, "not xml"));
        assertThrows(XStreamException.class, () -> read(reader, ""));
    }

    @Test
    public void nonNumericIntegrationIdIsRejected() {
        assertThrows(XStreamException.class,
                () -> read(reader, "<Project><Areas><Area Name=\"A\" IntegrationID=\"x\"/></Areas></Project>"));
    }

    @Test
    public void externalEntitiesInElementContentAreNotResolved(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET");
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Project [<!ENTITY xxe SYSTEM "%s">]>
                <Project><AppVer>&xxe;</AppVer></Project>
                """.formatted(secret.toUri());

        try {
            Project project = read(reader, xml);
            String version = project == null ? null : project.getAppVersion();
            assertFalse(String.valueOf(version).contains("TOPSECRET"), "external entity was resolved");
        } catch (XStreamException e) {
            // Rejecting the document is fine too
        }
    }

    @Test
    public void typesOutsideTheProjectModelAreNotInstantiated() {
        // XStream security: an XML document must not be able to create arbitrary classes
        assertThrows(XStreamException.class,
                () -> read(reader, "<java.util.ArrayList><java.lang.ProcessBuilder/></java.util.ArrayList>"));
    }
}
