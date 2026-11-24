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
package org.openhab.binding.lutron.internal.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link FanSpeedType}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class FanSpeedTypeTest {

    @ParameterizedTest
    @CsvSource({ "0, OFF", "1, LOW", "25, LOW", "26, MEDIUM", "50, MEDIUM", "51, MEDIUMHIGH", "75, MEDIUMHIGH",
            "76, HIGH", "100, HIGH", "101, HIGH" })
    public void percentagesMapToTheNearestSpeedAtOrAbove(int percentage, FanSpeedType expected) {
        assertEquals(expected, FanSpeedType.toFanSpeedType(percentage));
    }

    @ParameterizedTest
    @EnumSource(FanSpeedType.class)
    public void speedAndLeapValueRoundTrip(FanSpeedType speed) {
        assertEquals(speed, FanSpeedType.toFanSpeedType(speed.speed()));
        assertEquals(speed, FanSpeedType.toFanSpeedType(speed.leapValue()));
        assertEquals(speed.leapValue(), speed.toString());
    }

    @Test
    public void leapValuesMatchIgnoringCase() {
        assertEquals(FanSpeedType.MEDIUMHIGH, FanSpeedType.toFanSpeedType("mediumhigh"));
        assertEquals(FanSpeedType.HIGH, FanSpeedType.toFanSpeedType("HIGH"));
    }

    @Test
    public void unknownLeapValueIsOff() {
        assertEquals(FanSpeedType.OFF, FanSpeedType.toFanSpeedType("Turbo"));
        assertEquals(FanSpeedType.OFF, FanSpeedType.toFanSpeedType(""));
    }
}
