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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.radiora.protocol.LocalZoneChangeFeedback;
import org.openhab.binding.lutron.internal.radiora.protocol.RadioRAFeedback;
import org.openhab.binding.lutron.internal.radiora.protocol.ZoneMapFeedback;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortEvent;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;

/**
 * Tests for {@link RS232Connection} with a mocked serial port.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class RS232ConnectionTest {
    private static final String PORT = "/dev/ttyUSB0";

    private final SerialPortManager manager = mock(SerialPortManager.class);
    private final SerialPortIdentifier identifier = mock(SerialPortIdentifier.class);
    private final SerialPort port = mock(SerialPort.class);
    private final RS232Connection connection = new RS232Connection(manager);
    private final List<RadioRAFeedback> feedback = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        when(manager.getIdentifier(PORT)).thenReturn(identifier);
        when(identifier.getName()).thenReturn(PORT);
        when(identifier.open(anyString(), anyInt())).thenReturn(port);
        connection.setListener(feedback::add);
    }

    private void openWithInput(String input) throws Exception {
        when(port.getInputStream()).thenReturn(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)));
        connection.open(PORT, 9600);
    }

    private static SerialPortEvent event(int type) {
        SerialPortEvent event = mock(SerialPortEvent.class);
        when(event.getEventType()).thenReturn(type);
        return event;
    }

    @Test
    public void openConfiguresThePort() throws Exception {
        openWithInput("");

        verify(port).setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        verify(port).notifyOnDataAvailable(true);
        verify(port).addEventListener(connection);
    }

    @Test
    public void unknownPortIsAConnectionError() {
        assertThrows(RadioRAConnectionException.class, () -> connection.open("/dev/missing", 9600));
    }

    @Test
    public void portInUseIsAConnectionError() throws Exception {
        when(identifier.open(anyString(), anyInt())).thenThrow(new PortInUseException(new Exception("busy")));
        RadioRAConnectionException e = assertThrows(RadioRAConnectionException.class,
                () -> connection.open(PORT, 9600));
        assertTrue(String.valueOf(e.getMessage()).contains(PORT));
    }

    @Test
    public void inputStreamFailureIsAConnectionError() throws Exception {
        when(port.getInputStream()).thenThrow(new IOException("gone"));
        assertThrows(RadioRAConnectionException.class, () -> connection.open(PORT, 9600));
    }

    @Test
    public void eachDataEventDeliversOneMessage() throws Exception {
        openWithInput("LZC,4,ON\rZMP,1000000000000000000000000000000X\r");

        connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE));
        connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE));

        assertEquals(2, feedback.size());
        LocalZoneChangeFeedback change = assertInstanceOf(LocalZoneChangeFeedback.class, feedback.get(0));
        assertEquals(4, change.getZoneNumber());
        ZoneMapFeedback map = assertInstanceOf(ZoneMapFeedback.class, feedback.get(1));
        assertEquals('1', map.getZoneValue(1));
    }

    @Test
    public void malformedMessageDoesNotStopLaterMessages() throws Exception {
        openWithInput("LZC,abc,ON\rLZC\rLZC,4,ON\r");

        assertDoesNotThrow(() -> connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE)));
        assertDoesNotThrow(() -> connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE)));
        connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE));

        assertEquals(1, feedback.size());
        assertEquals(4, assertInstanceOf(LocalZoneChangeFeedback.class, feedback.get(0)).getZoneNumber());
    }

    @Test
    public void ignoredMessagesAndOtherEventsDeliverNothing() throws Exception {
        openWithInput("!\rHELLO\r");

        connection.serialEvent(event(SerialPortEvent.OUTPUT_BUFFER_EMPTY));
        connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE));
        connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE));

        assertTrue(feedback.isEmpty());
    }

    @Test
    public void dataEventWithNothingToReadIsIgnored() throws Exception {
        openWithInput("");
        assertDoesNotThrow(() -> connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE)));
        assertTrue(feedback.isEmpty());
    }

    @Test
    public void dataEventBeforeOpenIsIgnored() {
        assertDoesNotThrow(() -> connection.serialEvent(event(SerialPortEvent.DATA_AVAILABLE)));
    }

    @Test
    public void feedbackWithoutAListenerIsDropped() throws Exception {
        RS232Connection noListener = new RS232Connection(manager);
        when(port.getInputStream())
                .thenReturn(new ByteArrayInputStream("LZC,4,ON\r".getBytes(StandardCharsets.US_ASCII)));
        noListener.open(PORT, 9600);
        assertDoesNotThrow(() -> noListener.serialEvent(event(SerialPortEvent.DATA_AVAILABLE)));
    }

    @Test
    public void writeSendsTheCommandBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(port.getOutputStream()).thenReturn(out);
        openWithInput("");

        connection.write("SDL,3,50\r");

        assertEquals("SDL,3,50\r", out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void writeFailuresAreNotThrown() throws Exception {
        assertDoesNotThrow(() -> connection.write("ZMPI\r"), "not open");

        openWithInput("");
        assertDoesNotThrow(() -> connection.write("ZMPI\r"), "no output stream");

        OutputStream failing = mock(OutputStream.class);
        doThrow(new IOException("unplugged")).when(failing).write(any(byte[].class));
        when(port.getOutputStream()).thenReturn(failing);
        assertDoesNotThrow(() -> connection.write("ZMPI\r"), "write error");
    }

    @Test
    public void disconnectClosesThePort() throws Exception {
        openWithInput("");
        connection.disconnect();
        verify(port).close();
        assertDoesNotThrow(() -> new RS232Connection(manager).disconnect());
    }
}
