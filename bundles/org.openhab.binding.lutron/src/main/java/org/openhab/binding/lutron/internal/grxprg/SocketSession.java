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
package org.openhab.binding.lutron.internal.grxprg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a restartable socket connection to the underlying telnet session with a GRX-PRG/GRX-CI-PRG. Commands can
 * be sent via {@link #sendCommand(String)} and responses will be received on the {@link SocketSessionCallback}
 *
 * @author Tim Roberts - Initial contribution
 * @author Peter J Wojciechowski - Stop worker threads reliably on disconnect
 */
public class SocketSession {
    private final Logger logger = LoggerFactory.getLogger(SocketSession.class);

    /**
     * The uid of the calling thing
     */
    private final String uid;
    /**
     * The host/ip address to connect to
     */
    private final String host;

    /**
     * The port to connect to
     */
    private final int port;

    /**
     * The actual socket being used. Will be null if not connected
     */
    private Socket client;

    /**
     * The writer to the {@link #client}. Will be null if not connected
     */
    private PrintStream writer;

    /**
     * The reader from the {@link #client}. Will be null if not connected
     */
    private BufferedReader reader;

    /**
     * How long {@link #disconnect()} waits for each worker thread to finish
     */
    private static final long WORKER_STOP_TIMEOUT_MS = 5_000;

    /**
     * The responses read by the {@link ResponseReader}
     */
    private final BlockingQueue<Object> responsesQueue = new ArrayBlockingQueue<>(50);

    /**
     * Whether the workers of the current connection should keep running. Each connection has its own flag, so a
     * worker of an earlier connection can never be revived by a later one.
     */
    private AtomicBoolean workersActive = new AtomicBoolean(false);

    /**
     * The worker threads of the current connection. Null if not connected
     */
    private Thread readerThread;
    private Thread dispatcherThread;

    /**
     * The {@link SocketSessionCallback} that the {@link #dispatcher} will call
     */
    private AtomicReference<SocketSessionCallback> callback = new AtomicReference<>(null);

    /**
     * Creates the socket session from the given host and port
     *
     * @param uid the thing uid of the calling thing
     * @param host a non-null, non-empty host/ip address
     * @param port the port number between 1 and 65535
     */
    public SocketSession(String uid, String host, int port) {
        if (host == null || host.trim().length() == 0) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.uid = uid;
        this.host = host;
        this.port = port;
    }

    /**
     * Sets the {@link SocketSessionCallback} to use when calling back the
     * responses that have been received.
     *
     * @param callback a non-null {@link SocketSessionCallback} to use
     */
    public void setCallback(SocketSessionCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        this.callback.set(callback);
    }

    /**
     * Will attempt to connect to the {@link #host} on port {@link #port}. If we are current connected, will
     * {@link #disconnect()} first. Once connected, the {@link #writer} and {@link #reader} will be created and the
     * {@link Dispatcher} and {@link ResponseReader} threads will be started.
     *
     * @throws java.io.IOException if an exception occurs during the connection attempt
     */
    public void connect() throws IOException {
        disconnect();

        client = new Socket(host, port);
        client.setKeepAlive(true);
        client.setSoTimeout(1000); // allow reader to check to see if it should stop every 1 second

        logger.debug("Connecting to {}:{}", host, port);
        writer = new PrintStream(client.getOutputStream());
        reader = new BufferedReader(new InputStreamReader(client.getInputStream()));

        responsesQueue.clear();
        final AtomicBoolean active = new AtomicBoolean(true);
        workersActive = active;
        readerThread = new Thread(new ResponseReader(active, reader), "OH-binding-" + uid + "-responseReader");
        dispatcherThread = new Thread(new Dispatcher(active), "OH-binding-" + uid + "-dispatcher");
        readerThread.start();
        dispatcherThread.start();
    }

    /**
     * Disconnects from the {@link #host} if we are {@link #isConnected()}. The {@link #writer}, {@link #reader} and
     * {@link #client}
     * will be closed and set to null. The worker threads will be stopped, the {@link #callback} will be nulled and the
     * {@link #responsesQueue} will be cleared.
     *
     * @throws java.io.IOException if an exception occurs during the disconnect attempt
     */
    public void disconnect() throws IOException {
        if (isConnected()) {
            logger.debug("Disconnecting from {}:{}", host, port);

            // Stop the workers before closing the socket, so the reader does not report the close as a failure
            workersActive.set(false);

            writer.close();
            writer = null;

            reader.close();
            reader = null;

            client.close();
            client = null;

            stopWorker(readerThread);
            stopWorker(dispatcherThread);
            readerThread = null;
            dispatcherThread = null;

            callback.set(null);
            responsesQueue.clear();
        }
    }

    /**
     * Interrupts a worker thread and waits a bounded time for it to finish. A worker that disconnects its own session,
     * for example from a callback, is not waited for.
     */
    private void stopWorker(Thread worker) {
        if (worker == null || worker.equals(Thread.currentThread())) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(WORKER_STOP_TIMEOUT_MS);
            if (worker.isAlive()) {
                logger.warn("Worker thread {} did not stop", worker.getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns true if we are connected ({@link #client} is not null and is connected)
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * Sends the specified command to the underlying socket
     *
     * @param command a non-null, non-empty command
     * @throws java.io.IOException an exception that occurred while sending
     */
    public synchronized void sendCommand(String command) throws IOException {
        if (command == null) {
            throw new IllegalArgumentException("command cannot be null");
        }

        if (!isConnected()) {
            throw new IOException("Cannot send message - disconnected");
        }

        logger.debug("Sending Command: '{}'", command);
        writer.println(command + "\n"); // as pre spec - each command must have a newline
        writer.flush();
    }

    /**
     * This is the runnable that will read from the socket and add messages to the responses queue (to be processed by
     * the dispatcher)
     *
     * @author Tim Roberts
     *
     */
    private class ResponseReader implements Runnable {
        private final AtomicBoolean active;
        private final BufferedReader in;

        ResponseReader(AtomicBoolean active, BufferedReader in) {
            this.active = active;
            this.in = in;
        }

        /**
         * Runs the logic to read characters until {@link #active} is false. A response is considered a set of
         * characters that end with a carriage return/linefeed or with "login: ".
         */
        @Override
        public void run() {
            final StringBuilder sb = new StringBuilder(100);
            int c;

            while (active.get()) {
                try {
                    c = in.read();
                    if (c == -1) {
                        if (active.get()) {
                            responsesQueue.put(new IOException("server closed connection"));
                        }
                        break;
                    }
                    final char ch = (char) c;
                    sb.append(ch);
                    if (ch == '\n' || ch == ' ') {
                        final String str = sb.toString();
                        if (str.endsWith("\r\n") || str.endsWith("login: ")) {
                            sb.setLength(0);
                            final String response = str.substring(0, str.length() - 2);
                            logger.debug("Received response: {}", response);
                            responsesQueue.put(response);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // do nothing - we expect this (setSOTimeout) to check whether we should stop
                } catch (InterruptedException e) {
                    break; // stopping
                } catch (IOException e) {
                    if (active.get()) {
                        try {
                            responsesQueue.put(e);
                        } catch (InterruptedException e1) {
                            // stopping
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * The dispatcher runnable is responsible for reading the response queue and dispatching it to the current callable.
     * Since the dispatcher is ONLY started when a callable is set, responses may pile up in the queue and be dispatched
     * when a callable is set. Unlike the socket reader, this can be assigned to another thread (no state outside of
     * the class).
     *
     * @author Tim Roberts
     */
    private class Dispatcher implements Runnable {
        private final AtomicBoolean active;

        Dispatcher(AtomicBoolean active) {
            this.active = active;
        }

        @Override
        public void run() {
            while (active.get()) {
                try {
                    final SocketSessionCallback ssCallback = callback.get();

                    // if callback is null, we don't want to start dispatching yet.
                    if (ssCallback == null) {
                        Thread.sleep(250);
                        continue;
                    }

                    final Object response = responsesQueue.poll(1, TimeUnit.SECONDS);

                    // The callback may have been replaced while waiting, for example when a login completes, so
                    // deliver to the current one rather than to the one that was set when the wait began
                    final SocketSessionCallback current = callback.get();
                    if (response != null && (current == null || !active.get())) {
                        logger.debug("Dropping response received after disconnect: {}", response);
                    } else if (response != null) {
                        if (response instanceof String str) {
                            try {
                                logger.debug("Dispatching response: {}", response);
                                current.responseReceived(str);
                            } catch (Exception e) {
                                logger.warn("Exception occurred processing the response '{}': ", response, e);
                            }
                        } else if (response instanceof Exception exception) {
                            logger.debug("Dispatching exception: {}", response);
                            current.responseException(exception);
                        } else {
                            logger.error("Unknown response class: {}", response);
                        }
                    }
                } catch (InterruptedException e) {
                    break; // stopping
                }
            }
        }
    }
}
