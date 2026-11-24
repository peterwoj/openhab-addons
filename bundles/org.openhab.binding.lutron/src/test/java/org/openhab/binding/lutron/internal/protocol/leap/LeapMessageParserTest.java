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
package org.openhab.binding.lutron.internal.protocol.leap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.lutron.internal.protocol.FanSpeedType;
import org.openhab.binding.lutron.internal.protocol.leap.dto.Area;
import org.openhab.binding.lutron.internal.protocol.leap.dto.ButtonGroup;
import org.openhab.binding.lutron.internal.protocol.leap.dto.ButtonStatus;
import org.openhab.binding.lutron.internal.protocol.leap.dto.Device;
import org.openhab.binding.lutron.internal.protocol.leap.dto.OccupancyGroup;
import org.openhab.binding.lutron.internal.protocol.leap.dto.Project;
import org.openhab.binding.lutron.internal.protocol.leap.dto.ZoneExpanded;
import org.openhab.binding.lutron.internal.protocol.leap.dto.ZoneStatus;

/**
 * Tests for {@link LeapMessageParser}: dispatch of each LEAP message type and tolerance of malformed input.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("unchecked")
public class LeapMessageParserTest {

    private @NonNullByDefault({}) LeapMessageParserCallbacks callbacks;
    private @NonNullByDefault({}) LeapMessageParser parser;

    @BeforeEach
    public void setUp() {
        callbacks = mock(LeapMessageParserCallbacks.class);
        parser = new LeapMessageParser(callbacks);
    }

    private static String response(String communiqueType, String messageBodyType, String url, String body) {
        return """
                {"CommuniqueType":"%s","Header":{"MessageBodyType":"%s","StatusCode":"200 OK","Url":"%s"},"Body":%s}\
                """.formatted(communiqueType, messageBodyType, url, body);
    }

    private static String read(String messageBodyType, String body) {
        return response("ReadResponse", messageBodyType, "/test", body);
    }

    private void verifyOnlyValidMessage() {
        verify(callbacks).validMessageReceived(anyString());
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void oneZoneStatus() {
        parser.handleMessage(read("OneZoneStatus",
                """
                        {"ZoneStatus":{"href":"/zone/3/status","Level":75,"Zone":{"href":"/zone/3"},"StatusAccuracy":"Good","SwitchedLevel":"On"}}"""));

        verify(callbacks).validMessageReceived("ReadResponse");
        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        ZoneStatus status = captor.getValue();
        assertEquals(3, status.getZone());
        assertEquals(75, status.level);
        assertTrue(status.statusAccuracyGood());
        assertTrue(status.switchedLevelOn());
        assertNull(status.fanSpeed);
    }

    @Test
    public void fanZoneStatusCarriesItsSpeed() {
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"FanSpeed":"MediumHigh","Zone":{"href":"/zone/8"}}}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        assertEquals(FanSpeedType.MEDIUMHIGH, captor.getValue().fanSpeed);
    }

    @Test
    public void multipleZoneStatusDispatchesEachZone() {
        parser.handleMessage(read("MultipleZoneStatus", """
                {"ZoneStatuses":[{"Level":10,"Zone":{"href":"/zone/1"}},{"Level":20,"Zone":{"href":"/zone/2"}}]}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks, times(2)).handleZoneUpdate(captor.capture());
        assertEquals(List.of(1, 2), captor.getAllValues().stream().map(ZoneStatus::getZone).toList());
    }

    @Test
    public void updateAndSubscribeResponsesAreDispatchedLikeReads() {
        String body = """
                {"ZoneStatus":{"Level":5,"Zone":{"href":"/zone/4"}}}""";
        parser.handleMessage(response("UpdateResponse", "OneZoneStatus", "/zone/4/status", body));
        parser.handleMessage(response("SubscribeResponse", "OneZoneStatus", "/zone/status", body));

        verify(callbacks).validMessageReceived("UpdateResponse");
        verify(callbacks).validMessageReceived("SubscribeResponse");
        verify(callbacks, times(2)).handleZoneUpdate(any());
    }

    @Test
    public void buttonStatusEvent() {
        parser.handleMessage(read("OneButtonStatusEvent", """
                {"ButtonStatus":{"Button":{"href":"/button/101"},"ButtonEvent":{"EventType":"Press"}}}"""));

        ArgumentCaptor<ButtonStatus> captor = ArgumentCaptor.forClass(ButtonStatus.class);
        verify(callbacks).handleButtonStatus(captor.capture());
        assertEquals(101, captor.getValue().getButton());
        assertEquals("Press", captor.getValue().buttonEvent.eventType);
    }

    @Test
    public void multipleDeviceDefinition() {
        parser.handleMessage(read("MultipleDeviceDefinition", """
                {"Devices":[\
                {"href":"/device/1","Name":"Smart Bridge","ModelNumber":"L-BDG2-WH","SerialNumber":"1234",\
                "DeviceType":"SmartBridge","RepeaterProperties":{"IsRepeater":true}},\
                {"href":"/device/2","Name":"Dimmer","FullyQualifiedName":["Kitchen","Dimmer"],\
                "DeviceType":"WallDimmer","LocalZones":[{"href":"/zone/1"}],"AssociatedArea":{"href":"/area/2"}}]}"""));

        ArgumentCaptor<List<Device>> captor = ArgumentCaptor.forClass(List.class);
        verify(callbacks).handleMultipleDeviceDefinition(captor.capture());
        List<Device> devices = captor.getValue();
        assertEquals(2, devices.size());
        assertEquals(1, devices.get(0).getDevice());
        assertNotNull(devices.get(0).repeaterProperties);
        assertEquals("L-BDG2-WH", devices.get(0).modelNumber);
        Device dimmer = devices.get(1);
        assertEquals(2, dimmer.getDevice());
        assertEquals(1, dimmer.getZone());
        assertEquals(2, dimmer.getArea());
        assertEquals("Kitchen Dimmer", dimmer.getFullyQualifiedName());
    }

    @Test
    public void oneDeviceDefinition() {
        parser.handleMessage(read("OneDeviceDefinition", """
                {"Device":{"href":"/device/1","Name":"Processor","IsThisDevice":true}}"""));

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(callbacks).handleDeviceDefinition(captor.capture());
        assertTrue(captor.getValue().isThisDevice);
    }

    @Test
    public void multipleButtonGroupDefinition() {
        parser.handleMessage(read("MultipleButtonGroupDefinition", """
                {"ButtonGroups":[{"href":"/buttongroup/2","Parent":{"href":"/device/3"},\
                "Buttons":[{"href":"/button/101"},{"href":"/button/102"},{"href":"/button/103"}]}]}"""));

        ArgumentCaptor<List<ButtonGroup>> captor = ArgumentCaptor.forClass(List.class);
        verify(callbacks).handleMultipleButtonGroupDefinition(captor.capture());
        ButtonGroup group = captor.getValue().get(0);
        assertEquals(2, group.getButtonGroup());
        assertEquals(3, group.getParentDevice());
        assertEquals(List.of(101, 102, 103), group.getButtonList());
    }

    @Test
    public void noContentForButtonGroupsMeansNoButtons() {
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"StatusCode":"204 NoContent","Url":"/buttongroup"}}""");

        verify(callbacks).handleEmptyButtonGroupDefinition();
        verify(callbacks, never()).handleMultipleButtonGroupDefinition(any());
    }

    @Test
    public void noContentForOtherUrlsIsIgnored() {
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"StatusCode":"204 NoContent","Url":"/area"}}""");

        verifyOnlyValidMessage();
    }

    @Test
    public void areasOccupancyGroupsAndExpandedZones() {
        parser.handleMessage(read("MultipleAreaDefinition", """
                {"Areas":[{"href":"/area/2","Name":"Kitchen"},{"href":"/area/3","Name":"Den"}]}"""));
        parser.handleMessage(read("MultipleOccupancyGroupDefinition", """
                {"OccupancyGroups":[{"href":"/occupancygroup/5","AssociatedAreas":[{"Area":{"href":"/area/2"}}]}]}"""));
        parser.handleMessage(read("MultipleZoneExpandedStatus",
                """
                        {"ZoneExpandedStatuses":[{"href":"/zone/1/status","Level":0,"Zone":{"href":"/zone/1","Name":"Lamp"}}]}"""));

        ArgumentCaptor<List<Area>> areas = ArgumentCaptor.forClass(List.class);
        verify(callbacks).handleMultipleAreaDefinition(areas.capture());
        assertEquals(List.of(2, 3), areas.getValue().stream().map(Area::getArea).toList());

        ArgumentCaptor<List<OccupancyGroup>> groups = ArgumentCaptor.forClass(List.class);
        verify(callbacks).handleMultipleOccupancyGroupDefinition(groups.capture());
        OccupancyGroup group = groups.getValue().get(0);
        assertEquals(5, group.getOccupancyGroup());
        assertEquals(2, group.associatedAreas[0].getAreaNumber());

        ArgumentCaptor<List<ZoneExpanded>> zones = ArgumentCaptor.forClass(List.class);
        verify(callbacks).handleMultipleZoneExpandedUpdate(zones.capture());
        assertEquals(1, zones.getValue().get(0).zone.getZone());
        assertEquals("Lamp", zones.getValue().get(0).zone.name);
    }

    @Test
    public void occupancyGroupStatusSkipsUnnumberedGroups() {
        parser.handleMessage(read("MultipleOccupancyGroupStatus", """
                {"OccupancyGroupStatuses":[\
                {"OccupancyGroup":{"href":"/occupancygroup/2"},"OccupancyStatus":"Occupied"},\
                {"OccupancyGroup":{"href":"/occupancygroup/x"},"OccupancyStatus":"Occupied"},\
                {"OccupancyStatus":"Unoccupied"},\
                {"OccupancyGroup":{"href":"/occupancygroup/3"},"OccupancyStatus":"Unknown"}]}"""));

        verify(callbacks).handleGroupUpdate(2, "Occupied");
        verify(callbacks).handleGroupUpdate(3, "Unknown");
        verify(callbacks, times(2)).handleGroupUpdate(anyInt(), any());
    }

    @Test
    public void projectDefinition() {
        parser.handleMessage(read("OneProjectDefinition", """
                {"Project":{"href":"/project","Name":"Home","ProductType":"Lutron RadioRA 3 Project",\
                "MasterDeviceList":{"Devices":[{"href":"/device/7"}]}}}"""));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(callbacks).handleProjectDefinition(captor.capture());
        assertEquals("Lutron RadioRA 3 Project", captor.getValue().productType);
        assertEquals(7, captor.getValue().masterDeviceList.getDeviceIdFromHref(0));
    }

    @Test
    public void pingAndVirtualButtonsOnlyConfirmTheConnection() {
        parser.handleMessage(read("OnePingResponse", """
                {"PingResponse":{"LEAPVersion":1.115}}"""));
        parser.handleMessage(read("MultipleVirtualButtonDefinition", """
                {"VirtualButtons":[]}"""));

        verify(callbacks, times(2)).validMessageReceived("ReadResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void createResponseOnlyConfirmsTheConnection() {
        parser.handleMessage(response("CreateResponse", "OneZoneStatus", "/zone/3/commandprocessor", """
                {"ZoneStatus":{"Level":5,"Zone":{"href":"/zone/3"}}}"""));

        verify(callbacks).validMessageReceived("CreateResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void exceptionResponseOnlyConfirmsTheConnection() {
        parser.handleMessage("""
                {"CommuniqueType":"ExceptionResponse","Header":{"MessageBodyType":"ExceptionDetail",\
                "StatusCode":"400 BadRequest","Url":"/zone/99/commandprocessor"},"Body":{"Message":"Bad zone"}}""");
        parser.handleMessage("""
                {"CommuniqueType":"ExceptionResponse","Header":{"StatusCode":"404 NotFound","Url":"/x"}}""");
        parser.handleMessage("""
                {"CommuniqueType":"ExceptionResponse","Header":"broken"}""");

        verify(callbacks, times(3)).validMessageReceived("ExceptionResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void unknownCommuniqueTypeAndBodyTypeAreIgnored() {
        parser.handleMessage(response("FutureResponse", "OneZoneStatus", "/x", "{}"));
        parser.handleMessage(read("OneFutureThing", "{}"));

        verify(callbacks).validMessageReceived("FutureResponse");
        verify(callbacks).validMessageReceived("ReadResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void unknownFieldsAreIgnored() {
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"Level":42,"Zone":{"href":"/zone/3"},"Vibrancy":7,"Extra":{"Nested":[1,2]}}}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        assertEquals(42, captor.getValue().level);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "\t", "\r" })
    public void blankLinesAreIgnoredSilently(String line) {
        parser.handleMessage(line);
        verifyNoInteractions(callbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{", "{\"CommuniqueType\":", "{\"CommuniqueType\":\"ReadResponse\",\"Header\":{",
            "not json at all", "}{", "{\"a\":1}{\"b\":2}" })
    public void unparsableLinesAreIgnored(String line) {
        assertDoesNotThrow(() -> parser.handleMessage(line));
        verify(callbacks, never()).handleZoneUpdate(any());
    }

    @Test
    public void messageWithoutCommuniqueTypeIsIgnored() {
        parser.handleMessage("""
                {"Header":{"MessageBodyType":"OneZoneStatus"},"Body":{"ZoneStatus":{"Level":5}}}""");
        verifyNoInteractions(callbacks);
    }

    @Test
    public void missingBodyOrBodyTypeIsIgnored() {
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneZoneStatus","Url":"/zone/1"}}""");
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"Url":"/zone/1"},"Body":{"ZoneStatus":{"Level":5}}}""");

        verify(callbacks, times(2)).validMessageReceived("ReadResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void wronglyShapedHeaderOrBodyIsIgnored() {
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":"text","Body":{}}""");
        parser.handleMessage("""
                {"CommuniqueType":"ReadResponse","Header":{"MessageBodyType":"OneZoneStatus"},"Body":[1,2]}""");
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":"oops"}"""));
        parser.handleMessage(read("OneZoneStatus", """
                {"Status":{"Level":5}}"""));

        verify(callbacks, times(4)).validMessageReceived("ReadResponse");
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    public void fieldWithTheWrongTypeDropsOnlyThatMessage() {
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"Level":"bright","Zone":{"href":"/zone/3"}}}"""));
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"Level":30,"Zone":{"href":"/zone/3"}}}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        assertEquals(30, captor.getValue().level);
    }

    @Test
    public void unknownFanSpeedIsNotAFanUpdate() {
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"FanSpeed":"Turbo","Level":10,"Zone":{"href":"/zone/8"}}}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        assertNull(captor.getValue().fanSpeed);
    }

    @Test
    public void nonObjectListEntryDoesNotDropEarlierEntries() {
        parser.handleMessage(read("MultipleZoneStatus", """
                {"ZoneStatuses":[{"Level":10,"Zone":{"href":"/zone/1"}},7,null]}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks, atLeastOnce()).handleZoneUpdate(captor.capture());
        assertEquals(1, captor.getAllValues().get(0).getZone());
    }

    @Test
    public void listMemberThatIsNotAnArrayGivesAnEmptyList() {
        parser.handleMessage(read("MultipleDeviceDefinition", """
                {"Devices":{"href":"/device/1"}}"""));

        verify(callbacks).handleMultipleDeviceDefinition(List.of());
    }

    @Test
    public void missingListMemberGivesAnEmptyList() {
        parser.handleMessage(read("MultipleAreaDefinition", "{}"));

        verify(callbacks).handleMultipleAreaDefinition(List.of());
    }

    @Test
    public void parserKeepsWorkingAfterGarbage() {
        parser.handleMessage("{");
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"Level":"x"}}"""));
        parser.handleMessage(read("OneZoneStatus", """
                {"ZoneStatus":{"Level":99,"Zone":{"href":"/zone/2"}}}"""));

        ArgumentCaptor<ZoneStatus> captor = ArgumentCaptor.forClass(ZoneStatus.class);
        verify(callbacks).handleZoneUpdate(captor.capture());
        assertEquals(99, captor.getValue().level);
    }
}
