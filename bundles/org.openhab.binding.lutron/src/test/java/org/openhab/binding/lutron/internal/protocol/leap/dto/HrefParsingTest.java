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
package org.openhab.binding.lutron.internal.protocol.leap.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.Gson;

/**
 * Tests for the href-to-number conversion of the LEAP DTOs, which the bridge relies on to map zones, devices and
 * buttons. Unusable hrefs must map to 0, which callers treat as "not present".
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class HrefParsingTest {
    private final Gson gson = new Gson();

    private <T> T parse(String json, Class<T> type) {
        return Objects.requireNonNull(gson.fromJson(json, type));
    }

    private ZoneStatus zoneStatus(String zoneHref) {
        return parse("{\"Zone\":{\"href\":\"" + zoneHref + "\"}}", ZoneStatus.class);
    }

    @ParameterizedTest
    @CsvSource(value = { "/zone/3, 3", "/zone/0, 0", "/zone/2147483647, 2147483647", "/zone/2147483648, 0",
            "/zone/99999999999, 0", "/zone/abc, 0", "/zone/-5, 0", "/zone/, 0", "zone/4, 0", "/device/4, 0",
            "/zone/12/status, 12", "'', 0" })
    public void zoneHref(String href, int expected) {
        assertEquals(expected, zoneStatus(href).getZone());
    }

    @Test
    public void missingZoneIsZero() {
        assertEquals(0, parse("{}", ZoneStatus.class).getZone());
        assertEquals(0, parse("{\"Zone\":null}", ZoneStatus.class).getZone());
        assertEquals(0, parse("{\"Zone\":{\"href\":null}}", ZoneStatus.class).getZone());
    }

    @Test
    public void deviceUsesItsFirstLocalZone() {
        Device device = parse("""
                {"href":"/device/9","LocalZones":[{"href":"/zone/4"},{"href":"/zone/5"}]}""", Device.class);
        assertEquals(9, device.getDevice());
        assertEquals(4, device.getZone());
    }

    @Test
    public void deviceWithoutZonesOrArea() {
        Device device = parse("""
                {"href":"/device/9","LocalZones":[]}""", Device.class);
        assertEquals(0, device.getZone());
        assertEquals(0, device.getArea());
        assertEquals("", device.getFullyQualifiedName());
        assertEquals(0, parse("{}", Device.class).getDevice());
    }

    @Test
    public void nullHrefsAndEntriesMapToZero() {
        assertEquals(0, parse("{\"AssociatedArea\":{\"href\":null}}", Device.class).getArea());
        assertEquals(0, parse("{\"AssociatedArea\":null}", Device.class).getArea());
        assertEquals(0, parse("{\"LocalZones\":[null]}", Device.class).getZone());
        OccupancyGroup group = parse("{\"AssociatedAreas\":[{\"Area\":null}]}", OccupancyGroup.class);
        assertEquals(0, group.associatedAreas[0].getAreaNumber());
    }

    @Test
    public void buttonGroupWithoutButtonsHasNone() {
        assertEquals(List.of(), parse("{\"href\":\"/buttongroup/2\"}", ButtonGroup.class).getButtonList());
        assertEquals(List.of(4),
                parse("{\"Buttons\":[null,{\"href\":\"/button/4\"}]}", ButtonGroup.class).getButtonList());
    }

    @Test
    public void buttonGroupWithoutParent() {
        ButtonGroup group = parse("""
                {"href":"/buttongroup/2","Parent":null,"Buttons":[{"href":"/button/7"},{"href":"/button/x"}]}""",
                ButtonGroup.class);
        assertEquals(0, group.getParentDevice());
        assertEquals(List.of(7, 0), group.getButtonList());
    }

    @Test
    public void buttonStatusWithoutButton() {
        assertEquals(0, parse("{\"Button\":null}", ButtonStatus.class).getButton());
        assertEquals(0, parse("{}", ButtonStatus.class).getButton());
    }

    @Test
    public void occupancyGroupStatusWithoutGroup() {
        assertEquals(0, parse("{\"OccupancyStatus\":\"Occupied\"}", OccupancyGroupStatus.class).getOccupancyGroup());
    }

    @Test
    public void emptyMasterDeviceList() {
        Project project = parse("""
                {"MasterDeviceList":{"Devices":[]}}""", Project.class);
        assertEquals(0, project.masterDeviceList.getDeviceIdFromHref(0));
    }

    @Test
    public void areaHref() {
        assertEquals(12, parse("{\"href\":\"/area/12\"}", Area.class).getArea());
        assertEquals(0, parse("{\"href\":\"/area/\"}", Area.class).getArea());
    }
}
