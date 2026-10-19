package com.ltm.rd.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.function.UnaryOperator;

/**
 * A socket with its stream wrappers. The wrappers are created once and handed over as a unit
 * (never re-wrap the socket streams: a BufferedInputStream may already hold prefetched bytes).
 */
public record Channel(Socket socket, DataInputStream in, DataOutputStream out) {

    public static Channel wrap(Socket socket, TrafficCounter counter) throws IOException {
        return wrap(socket, counter, UnaryOperator.identity());
    }

    /**
     * As {@link #wrap(Socket, TrafficCounter)}, but {@code outputDecorator} wraps the raw socket
     * output before it is counted and buffered (e.g. a throttle in tests); still one wrapper set.
     */
    public static Channel wrap(Socket socket, TrafficCounter counter, UnaryOperator<OutputStream> outputDecorator)
            throws IOException {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(counter.countingInput(socket.getInputStream())));
        OutputStream raw = outputDecorator.apply(socket.getOutputStream());
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(counter.countingOutput(raw)));
        return new Channel(socket, in, out);
    }

    /** Closes only the socket; never flushes the wrappers, so blocked readers/writers are released. */
    public void closeRawSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    /** Call after the socket is closed: closing a buffered stream may try to flush. */
    public void closeWrappersQuietly() {
        try {
            out.close();
        } catch (IOException ignored) {
            // flush of buffered bytes fails on a closed socket; expected
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
