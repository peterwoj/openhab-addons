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
package org.openhab.binding.lutron.internal.protocol.leap.dto;

import org.openhab.binding.lutron.internal.protocol.FanSpeedType;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * LeapRequest object
 *
 * @author Peter J Wojciechowski - initial
 */
public class LeapRequest {
    @SerializedName("CommuniqueType")
    public String communiqueType = "";
    @SerializedName("Header")
    public Header header = new Header();
    @SerializedName("Body")
    public Body body;

    public static class Body {
        @SerializedName("Command")
        public Command command;

        public static class Command {
            @SerializedName("CommandType")
            public String commandType;
            @SerializedName("Parameter")
            public CommandParameter commandParameter;
            @SerializedName("DimmedLevelParameters")
            public Command.DimmedLevelParameters dimmedLevelParameters;
            @SerializedName("FanSpeedParameters")
            public FanSpeedParameters fanSpeedParameters;

            public static class CommandParameter {
                @SerializedName("Type")
                public String type;
                @SerializedName("Value")
                public String value;
            }

            public static class DimmedLevelParameters {
                public DimmedLevelParameters() {
                }

                public DimmedLevelParameters(Integer level) {
                    this.level = level;
                }

                public DimmedLevelParameters(Integer level, String fadeTime) {
                    this.level = level;
                    this.fadeTime = fadeTime;
                }

                public DimmedLevelParameters(Integer level, String fadeTime, String delayTime) {
                    this.level = level;
                    this.fadeTime = fadeTime;
                    this.delayTime = delayTime;
                }

                @SerializedName("Level")
                public Integer level;
                @SerializedName("FadeTime")
                public String fadeTime;
                @SerializedName("DelayTime")
                public String delayTime;
            }

            public static class FanSpeedParameters {
                public FanSpeedParameters() {
                }

                public FanSpeedParameters(FanSpeedType fanSpeed) {
                    this.fanSpeed = fanSpeed.leapValue();
                }

                @SerializedName("FanSpeed")
                public String fanSpeed;
            }
        }
    }

    public static class Header {
        @SerializedName("Url")
        public String url;
    }

    public String toString() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }
}
