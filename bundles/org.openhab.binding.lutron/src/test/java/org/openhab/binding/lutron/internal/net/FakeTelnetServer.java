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
package org.openhab.binding.lutron.internal.net;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A telnet peer on the loopback interface standing in for a Lutron bridge. It accepts any number of connections and
 * hides telnet option negotiation from the test, which only sees the text lines the binding sends.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
public class FakeTelnetServer implements AutoCloseable {
    public static final long WAIT_SECONDS = 20;

    private static final int IAC = 255;
    private static final int SB = 250;
    private static final int SE = 240;
    private static final int WILL = 251;
    private static final int DONT = 254;

    /**
     * One accepted connection.
     */
    public static class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            // Bounded, so that a regression fails the test instead of hanging the build
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            this.in = socket.getInputStream();
        }

        public void send(String text) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        public void sendBytes(byte[] bytes) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
        }

        /**
         * Returns the next line sent by the binding, without its line terminator, or null if the binding closed the
         * connection. Fails the test if nothing arrives in time.
         */
        public @Nullable String readLine() throws IOException {
            return readLine(socket.getSoTimeout());
        }

        public @Nullable String readLine(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b == -1) {
                    return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
                } else if (b == IAC) {
                    skipTelnetCommand();
                } else if (b == '\n') {
                    String text = line.toString(StandardCharsets.ISO_8859_1);
                    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                } else {
                    line.write(b);
                }
            }
        }

        /**
         * Returns the next line, or null if nothing arrives within the given time.
         */
        public @Nullable String pollLine(int timeoutMs) throws IOException {
            try {
                return readLine(timeoutMs);
            } catch (SocketTimeoutException e) {
                return null;
            }
        }

        public String expectLine() throws IOException {
            String line = readLine();
            assertNotNull(line, "the binding closed the connection");
            return line;
        }

        private void skipTelnetCommand() throws IOException {
            int command = in.read();
            if (command >= WILL && command <= DONT) {
                in.read(); // the option
            } else if (command == SB) {
                int previous = 0;
                int b;
                while ((b = in.read()) != -1 && !(previous == IAC && b == SE)) {
                    previous = b;
                }
            }
        }

        public boolean isClosedByPeer() throws IOException {
            socket.setSoTimeout(200);
            try {
                return in.read() == -1;
            } catch (SocketTimeoutException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private final ServerSocket server;
    private final BlockingQueue<Connection> accepted = new LinkedBlockingQueue<>();
    private final List<Connection> all = new CopyOnWriteArrayList<>();

    public FakeTelnetServer() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Connection connection = new Connection(server.accept());
                    all.add(connection);
                    accepted.add(connection);
                } catch (IOException e) {
                    // closed by the test
                }
            }
        }, "fake-telnet-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /**
     * Waits for the binding to connect.
     */
    public Connection accept() throws InterruptedException {
        Connection connection = accepted.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(connection, "the binding did not connect");
        return connection;
    }

    public @Nullable Connection pollAccept(long timeoutMs) throws InterruptedException {
        return accepted.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Connection connection : all) {
            connection.close();
        }
    }
}
