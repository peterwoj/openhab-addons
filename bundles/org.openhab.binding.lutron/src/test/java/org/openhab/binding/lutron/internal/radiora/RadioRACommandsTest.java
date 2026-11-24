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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.radiora.protocol.ButtonPressCommand;
import org.openhab.binding.lutron.internal.radiora.protocol.SetDimmerLevelCommand;
import org.openhab.binding.lutron.internal.radiora.protocol.SetSwitchLevelCommand;
import org.openhab.binding.lutron.internal.radiora.protocol.ZoneMapInquiryCommand;
import org.openhab.core.library.types.OnOffType;

/**
 * Tests for the RadioRA RS232 command strings.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class RadioRACommandsTest {

    @Test
    public void setDimmerLevel() {
        assertEquals("SDL,3,50\r", new SetDimmerLevelCommand(3, 50, 0).toString());

        SetDimmerLevelCommand withFade = new SetDimmerLevelCommand(3, 50, 1);
        withFade.setFadeSeconds(5);
        assertEquals("SDL,3,50,5,S1\r", withFade.toString());
    }

    @Test
    public void setSwitchLevel() {
        assertEquals("SSL,2,OFF\r", new SetSwitchLevelCommand(2, OnOffType.OFF, 0).toString());

        SetSwitchLevelCommand withDelay = new SetSwitchLevelCommand(2, OnOffType.ON, 2);
        withDelay.setDelaySeconds(10);
        assertEquals("SSL,2,ON,10,S2\r", withDelay.toString());
    }

    @Test
    public void buttonPress() {
        assertEquals("BP,16,ON\r", new ButtonPressCommand(16, ButtonPressCommand.ButtonState.ON, 0).toString());

        ButtonPressCommand withFade = new ButtonPressCommand(5, ButtonPressCommand.ButtonState.TOG, 1);
        withFade.setFadeSeconds(3);
        assertEquals("BP,5,TOG,3,S1\r", withFade.toString());
    }

    @Test
    public void systemNumbersOtherThanOneOrTwoAreOmitted() {
        assertEquals("SDL,3,50\r", new SetDimmerLevelCommand(3, 50, 3).toString());
        assertEquals("SSL,3,ON\r", new SetSwitchLevelCommand(3, OnOffType.ON, -1).toString());
    }

    @Test
    public void zoneMapInquiry() {
        assertEquals("ZMPI\r", new ZoneMapInquiryCommand().toString());
    }
}
