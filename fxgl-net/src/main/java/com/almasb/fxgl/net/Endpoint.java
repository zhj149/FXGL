/*
 * FXGL - JavaFX Game Library. The MIT License (MIT).
 * Copyright (c) AlmasB (almaslvl@gmail.com).
 * See LICENSE for details.
 */

package com.almasb.fxgl.net;

import com.almasb.fxgl.core.serialization.Bundle;
import com.almasb.fxgl.logging.Logger;
import com.almasb.fxgl.net.tcp.TCPConnection;
import com.almasb.fxgl.net.udp.UDPConnection;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A single endpoint of a connection, i.e. client or server.
 *
 * @author Almas Baimagambetov (almaslvl@gmail.com)
 * @author Jordan O'Hara (jordanohara96@gmail.com)
 * @author Byron Filer (byronfiler348@gmail.com)
 */
public abstract class Endpoint<T> {

    private static final Logger log = Logger.get(Endpoint.class);

    // TODO: observable?
    private List<Connection<T>> connections = new ArrayList<>();

    private Consumer<Connection<T>> onConnected = c -> {};
    private Consumer<Connection<T>> onDisconnected = c -> {};

    /**
     * Send given message to all active connections.
     */
    public final void broadcast(T message) {
        for (int i = 0; i < connections.size(); i++) {
            connections.get(i).send(message);
        }
    }

    /**
     * The given callback function is called when a new connection with another Endpoint has been established.
     * Message handlers should be added within the callback function.
     * It is also safe to call connection.send() or broadcast() within the callback function.
     * Such messages will arrive in correct order provided that the other Endpoint also added message handlers
     * within the callback function.
     */
    public final void setOnConnected(Consumer<Connection<T>> onConnected) {
        this.onConnected = onConnected;
    }

    public final void setOnDisconnected(Consumer<Connection<T>> onDisconnected) {
        this.onDisconnected = onDisconnected;
    }

    protected final void openTCPConnection(Socket socket, int connectionNum, Class<T> messageType) throws Exception {
        log.debug(getClass().getSimpleName() + " opening new connection (" + connectionNum + ") from " + socket.getInetAddress() + ":" + socket.getPort() + " type: " + messageType);

        socket.setTcpNoDelay(true);

        Connection<T> connection = new TCPConnection<T>(socket, connectionNum);

        onConnectionOpened(connection);

        new ConnectionThread(getClass().getSimpleName() + "_SendThread-" + connectionNum, () -> {

            try {
                var writer = Writers.INSTANCE.getWriter(Protocol.TCP, messageType, socket.getOutputStream());

                while (connection.isConnected()) {
                    var message = connection.messageQueue.take();

                    writer.write(message);
                }
            } catch (Exception e) {

                // TODO:
                e.printStackTrace();
            }
        }).start();

        new ConnectionThread(getClass().getSimpleName() +"_RecvThread-" + connectionNum, () -> {
            try {
                var reader = Readers.INSTANCE.getReader(messageType, socket.getInputStream());

                while (connection.isConnected()) {
                    try {
                        var message = reader.read();

                        connection.notifyMessageReceived(message);

                    } catch (EOFException e) {
                        log.debug("Connection " + connectionNum + " was correctly closed from remote endpoint.");

                        connection.terminate();
                    } catch (SocketException e) {

                        if (!connection.isClosedLocally()) {
                            log.debug("Connection " + connectionNum + " was unexpectedly disconnected: " + e.getMessage());

                            connection.terminate();
                        }

                    } catch (Exception e) {
                        log.warning("Connection " + connectionNum + " had unspecified error during receive()", e);

                        connection.terminate();
                    }
                }
            } catch (Exception e) {

                // TODO:
                e.printStackTrace();
            }

            onConnectionClosed(connection);
        }).start();
    }

    protected final void openUDPConnection(UDPConnection<T> connection, Class<T> messageType) {
        log.debug("Opening UDP connection (" + connection.getConnectionNum() + ")");

        onConnectionOpened(connection);

        new ConnectionThread(getClass().getSimpleName() + "_SendThread-" + connection.getConnectionNum(), () -> {

            try {
                while (connection.isConnected()) {
                    var message = connection.messageQueue.take();

                    // TODO: convert to byte[] using UDP writers
                    var bytes = ((UDPWriter<T>) Writers.INSTANCE.getNewUDPWriters().get(messageType)).write(message);

                    connection.sendUDP(bytes);
                }
            } catch (Exception e) {

                // TODO:
                e.printStackTrace();
            }
        }).start();

        new ConnectionThread(getClass().getSimpleName() + "_RecvThread-" + connection.getConnectionNum(), () -> {

            try {
                while (connection.isConnected()) {
                    var bytes = connection.getRecvQueue().take();

                    // TODO: convert to T using UDP readers
                    try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                        var message = (T) ois.readObject();

                        ((Connection<T>) connection).notifyMessageReceived(message);
                    }
                }
            } catch (Exception e) {

                // TODO:
                e.printStackTrace();
            }
        }).start();
    }

    private void onConnectionOpened(Connection<T> connection) {
        log.debug(getClass().getSimpleName() + " successfully opened connection (" + connection.getConnectionNum() + ")");

        connections.add(connection);

        onConnected.accept(connection);
    }

    protected final void onConnectionClosed(Connection<T> connection) {
        log.debug(getClass().getSimpleName() + " connection (" + connection.getConnectionNum() + ") was closed");

        connections.remove(connection);

        onDisconnected.accept(connection);
    }

    /**
     * @return unmodifiable list of active connections (for clients, max size is 1)
     */
    public final List<Connection<T>> getConnections() {
        return List.copyOf(connections);
    }

    private static class ConnectionThread extends Thread {

        ConnectionThread(String name, Runnable action) {
            super(action, name);
            setDaemon(true);
        }
    }
}
