/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.lutron.internal.protocol.FanSpeedType;
import org.openhab.binding.lutron.internal.protocol.leap.dto.LeapRequest;

/**
 * Contains static methods for constructing LEAP messages
 *
 * @author Bob Adair - Initial contribution
 */
@NonNullByDefault
public class Request {
    public static final String BUTTON_GROUP_URL = "/buttongroup";

    public static String goToLevel(int zone, int value) {
        return goToDimmedLevel(zone, value, "00:00:00");
    }

    /** fadeTime must be in the format hh:mm:ss **/
    public static String goToDimmedLevel(int zone, int value, String fadeTime) {
        return goToDimmedLevel(zone, value, fadeTime, "00:00:00");
    }

    /** fadeTime and delayTime must be in the format hh:mm:ss **/
    public static String goToDimmedLevel(int zone, int value, String fadeTime, String delayTime) {
        LeapRequest req = getCreateRequest(CommandType.GOTODIMMEDLEVEL,
                String.format("/zone/%d/commandprocessor", zone));
        req.body.command.dimmedLevelParameters = new LeapRequest.Body.Command.DimmedLevelParameters(value, fadeTime,
                delayTime);
        return req.toString();
    }

    public static String goToFanSpeed(int zone, FanSpeedType fanSpeed) {
        LeapRequest req = getCreateRequest(CommandType.GOTOFANSPEED, String.format("/zone/%d/commandprocessor", zone));
        req.body.command.fanSpeedParameters = new LeapRequest.Body.Command.FanSpeedParameters(fanSpeed);
        return req.toString();
    }

    public static String buttonCommand(int button, CommandType command) {
        LeapRequest req = getCreateRequest(command, String.format("/button/%d/commandprocessor", button));
        return req.toString();
    }

    public static String virtualButtonCommand(int virtualbutton, CommandType command) {
        LeapRequest req = getCreateRequest(command, String.format("/virtualbutton/%d/commandprocessor", virtualbutton));
        return req.toString();
    }

    public static String zoneCommand(int zone, CommandType commandType) {
        LeapRequest req = getCreateRequest(commandType, String.format("/zone/%d/commandprocessor", zone));
        return req.toString();
    }

    public static String request(CommuniqueType cType, String url) {
        LeapRequest leapRequest = getLeapRequest(cType, url);
        return leapRequest.toString();
    }

    private static LeapRequest getLeapRequest(CommuniqueType cType, String url) {
        LeapRequest leapRequest = new LeapRequest();
        leapRequest.communiqueType = cType.toString();
        leapRequest.header.url = url;

        return leapRequest;
    }

    private static LeapRequest getCreateRequest(CommandType commandType, String url) {
        LeapRequest leapRequest = getLeapRequest(CommuniqueType.CREATEREQUEST, url);

        leapRequest.body = new LeapRequest.Body();
        leapRequest.body.command = new LeapRequest.Body.Command();
        leapRequest.body.command.commandType = commandType.toString();

        return leapRequest;
    }

    public static String ping() {
        return request(CommuniqueType.READREQUEST, "/server/1/status/ping");
    }

    public static String getDevices() {
        return getDevices("");
    }

    public static String getDevices(boolean thisDevice) {
        String url = String.format("where=IsThisDevice:%s", (thisDevice) ? "true" : "false");

        return getDevices(url);
    }

    public static String getDevices(String predicate) {
        String url = "/device";
        if (!predicate.isEmpty()) {
            url = String.format("%s?%s", url, predicate);
        }

        return request(CommuniqueType.READREQUEST, url);
    }

    public static String getVirtualButtons() {
        return request(CommuniqueType.READREQUEST, "/virtualbutton");
    }

    public static String getButtonGroups() {
        return request(CommuniqueType.READREQUEST, BUTTON_GROUP_URL);
    }

    public static String getProject() {
        return request(CommuniqueType.READREQUEST, "/project");
    }

    public static String getAreas() {
        return request(CommuniqueType.READREQUEST, "/area");
    }

    public static String getOccupancyGroups() {
        return request(CommuniqueType.READREQUEST, "/occupancygroup");
    }

    public static String getZoneStatus(int zone) {
        return request(CommuniqueType.READREQUEST, String.format("/zone/%d/status", zone));
    }

    public static String getZoneStatuses() {
        return request(CommuniqueType.READREQUEST,
                "/zone/status/expanded?where=Zone.ControlType:\"Dimmed\"|\"Switched\"|\"CCO\"|\"Shade\"");
    }

    public static String getOccupancyGroupStatus() {
        return request(CommuniqueType.READREQUEST, "/occupancygroup/status");
    }

    public static String subscribeButtonStatus(int button) {
        return request(CommuniqueType.SUBSCRIBEREQUEST, String.format("/button/%d/status/event", button));
    }

    public static String subscribeOccupancyGroupStatus() {
        return request(CommuniqueType.SUBSCRIBEREQUEST, "/occupancygroup/status");
    }

    public static String subscribeZoneStatus() {
        return request(CommuniqueType.SUBSCRIBEREQUEST, "/zone/status");
    }

    public static String subscribeAreaStatus() {
        return request(CommuniqueType.SUBSCRIBEREQUEST, "/area/status");
    }
}
