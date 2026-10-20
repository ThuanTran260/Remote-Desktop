package com.ltm.rd.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Seam over {@code Socket.connect} so tests can block or fail a connect deterministically. */
@FunctionalInterface
public interface SocketConnector {
    SocketConnector DEFAULT = Socket::connect;

    void connect(Socket socket, InetSocketAddress address, int timeoutMs) throws IOException;
}
