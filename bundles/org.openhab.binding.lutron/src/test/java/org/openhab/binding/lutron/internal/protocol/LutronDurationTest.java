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

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link LutronDuration}.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class LutronDurationTest {

    @ParameterizedTest
    @CsvSource({ "0, 0, 0", "5, 0, 5", "5, 25, 5.25", "7, 5, 7.05", "99, 0, 99", "99, 99, 99.99", "100, 0, 1:40",
            "100, 50, 1:40", "3599, 0, 59:59", "3600, 0, 1:00:00", "3661, 0, 1:01:01", "359999, 0, 99:59:59" })
    public void lipStringSwitchesFormatAtTheProtocolBoundaries(int seconds, int hundredths, String expected) {
        assertEquals(expected, new LutronDuration(seconds, hundredths).asLipString());
    }

    @ParameterizedTest
    @CsvSource({ "0, 0, 00:00:00", "0, 1, 00:00:01", "2, 49, 00:00:02", "2, 50, 00:00:03", "59, 99, 00:01:00",
            "3661, 0, 01:01:01", "359999, 0, 99:59:59" })
    public void leapStringRoundsToWholeSeconds(int seconds, int hundredths, String expected) {
        assertEquals(expected, new LutronDuration(seconds, hundredths).asLeapString());
    }

    @ParameterizedTest
    @CsvSource({ "7, 7, 0", "07, 7, 0", "7.05, 7, 5", "0.25, 0, 25", "2:30, 150, 0", "1:02:03, 3723, 0",
            "99:59:59, 359999, 0" })
    public void parsesEveryDocumentedStringFormat(String text, int seconds, int hundredths) {
        LutronDuration duration = new LutronDuration(text);
        assertEquals(seconds, duration.seconds);
        assertEquals(hundredths, duration.hundredths);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "abc", "1:2", "123", "1.5", "1.234", "-1", "1:02:03:04", "1,5", "1:0x" })
    public void rejectsMalformedStrings(String text) {
        assertThrows(IllegalArgumentException.class, () -> new LutronDuration(text));
    }

    @Test
    public void integerConstructorsEnforceTheProtocolRange() {
        assertEquals(LutronDuration.MAX_SECONDS, new LutronDuration(LutronDuration.MAX_SECONDS).seconds);
        assertThrows(IllegalArgumentException.class, () -> new LutronDuration(-1));
        assertThrows(IllegalArgumentException.class, () -> new LutronDuration(LutronDuration.MAX_SECONDS + 1));
        assertThrows(IllegalArgumentException.class, () -> new LutronDuration(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new LutronDuration(1, LutronDuration.MAX_HUNDREDTHS + 1));
    }

    @Test
    public void decimalConstructorsSplitSecondsAndHundredths() {
        LutronDuration fromBigDecimal = new LutronDuration(new BigDecimal("12.34"));
        assertEquals(12, fromBigDecimal.seconds);
        assertEquals(34, fromBigDecimal.hundredths);

        // Digits beyond hundredths are dropped
        LutronDuration truncated = new LutronDuration(new BigDecimal("12.349"));
        assertEquals(34, truncated.hundredths);

        LutronDuration fromDouble = new LutronDuration(2.5);
        assertEquals(2, fromDouble.seconds);
        assertEquals(50, fromDouble.hundredths);
        assertEquals("2.50", fromDouble.asLipString());
    }

    @Test
    public void toStringIsTheLipRepresentation() {
        assertEquals("1:40", new LutronDuration(100).toString());
    }
}
