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
package org.openhab.binding.lutron.internal.radiora;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.binding.lutron.internal.radiora.protocol.LEDMapFeedback;
import org.openhab.binding.lutron.internal.radiora.protocol.LocalZoneChangeFeedback;
import org.openhab.binding.lutron.internal.radiora.protocol.RadioRAFeedback;
import org.openhab.binding.lutron.internal.radiora.protocol.ZoneMapFeedback;

/**
 * Tests for {@link RS232MessageParser} and the RadioRA feedback messages it produces.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class RS232MessageParserTest {
    private final RS232MessageParser parser = new RS232MessageParser();

    private <T extends RadioRAFeedback> T parse(String message, Class<T> type) {
        RadioRAFeedback feedback = parser.parse(message);
        assertNotNull(feedback, message);
        assertInstanceOf(type, feedback);
        return type.cast(feedback);
    }

    @Test
    public void ledMap() {
        LEDMapFeedback feedback = parse("LMP,100000000000001", LEDMapFeedback.class);
        assertEquals("100000000000001", feedback.getBitmap());
        assertEquals('1', feedback.getZoneValue(1));
        assertEquals('0', feedback.getZoneValue(2));
        assertEquals('1', feedback.getZoneValue(15));
    }

    @Test
    public void ledMapOutsideTheBitmapIsOff() {
        LEDMapFeedback feedback = parse("LMP,11", LEDMapFeedback.class);
        assertEquals('0', feedback.getZoneValue(0));
        assertEquals('0', feedback.getZoneValue(3));
        assertEquals('0', feedback.getZoneValue(-1));
    }

    @Test
    public void localZoneChange() {
        LocalZoneChangeFeedback feedback = parse("LZC,04,CHG", LocalZoneChangeFeedback.class);
        assertEquals(4, feedback.getZoneNumber());
        assertEquals(LocalZoneChangeFeedback.State.CHG, feedback.getState());
        assertEquals(0, feedback.getSystem());
    }

    @Test
    public void localZoneChangeIsLenientAboutCaseAndSpacing() {
        LocalZoneChangeFeedback feedback = parse("LZC, 12 , on ,s2", LocalZoneChangeFeedback.class);
        assertEquals(12, feedback.getZoneNumber());
        assertEquals(LocalZoneChangeFeedback.State.ON, feedback.getState());
        assertEquals(2, feedback.getSystem());
    }

    @Test
    public void localZoneChangeWithAnUnknownSystemHasNone() {
        assertEquals(1, parse("LZC,1,OFF,S1", LocalZoneChangeFeedback.class).getSystem());
        assertEquals(0, parse("LZC,1,OFF,S3", LocalZoneChangeFeedback.class).getSystem());
    }

    @Test
    public void zoneMap() {
        ZoneMapFeedback feedback = parse("ZMP,010X0000000000000000000000000001,S1", ZoneMapFeedback.class);
        assertEquals('0', feedback.getZoneValue(1));
        assertEquals('1', feedback.getZoneValue(2));
        assertEquals('X', feedback.getZoneValue(4));
        assertEquals('1', feedback.getZoneValue(32));
        assertEquals(1, feedback.getSystem());
    }

    @Test
    public void zoneMapOutsideTheMapIsUnknown() {
        ZoneMapFeedback feedback = parse("ZMP,01", ZoneMapFeedback.class);
        assertEquals('X', feedback.getZoneValue(0));
        assertEquals('X', feedback.getZoneValue(3));
        assertEquals(0, feedback.getSystem());
        assertEquals(0, parse("ZMP,01,S9", ZoneMapFeedback.class).getSystem());
    }

    @ParameterizedTest
    @ValueSource(strings = { "!", "", ",,,", "XYZ,1,2", "lzc,1,ON", "OK", "ZMPI" })
    public void unknownOrIgnoredMessagesGiveNoFeedback(String message) {
        assertNull(parser.parse(message));
    }
}
