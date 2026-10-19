package com.ltm.rd.it;

import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.EnumSet;

/** One end of a raw connection that speaks PacketIO directly, for tests that play the other side. */
final class RawPeer implements AutoCloseable {
    private static final int READ_TIMEOUT_MS = 10_000;

    final Socket socket;
    final DataInputStream in;
    final DataOutputStream out;

    RawPeer(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(READ_TIMEOUT_MS);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    void send(Packet p) throws IOException {
        PacketIO.write(out, p);
        out.flush();
    }

    Packet read() throws IOException {
        return PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class))
                .orElseThrow(() -> new EOFException("peer closed"));
    }

    /** Reads until a packet of {@code type} arrives, skipping PING/PONG. */
    Packet readUntil(PacketType type) throws IOException {
        while (true) {
            Packet p = read();
            if (p.type() == type) return p;
            if (p.type() != PacketType.PING && p.type() != PacketType.PONG) {
                throw new IOException("unexpected " + p.type() + " while waiting for " + type);
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // test teardown
        }
    }

    /** A loopback server on two ephemeral ports, accepting on the caller's thread. */
    static final class Server implements AutoCloseable {
        private final ServerSocket cmd;
        private final ServerSocket img;

        Server() throws IOException {
            cmd = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            img = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            cmd.setSoTimeout(READ_TIMEOUT_MS);
            img.setSoTimeout(READ_TIMEOUT_MS);
        }

        int cmdPort() {
            return cmd.getLocalPort();
        }

        int imgPort() {
            return img.getLocalPort();
        }

        RawPeer acceptCmd() throws IOException {
            return new RawPeer(cmd.accept());
        }

        RawPeer acceptImg() throws IOException {
            return new RawPeer(img.accept());
        }

        @Override
        public void close() {
            try {
                cmd.close();
                img.close();
            } catch (IOException ignored) {
                // test teardown
            }
        }
    }
}
