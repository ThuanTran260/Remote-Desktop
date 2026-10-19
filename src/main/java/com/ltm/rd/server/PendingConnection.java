package com.ltm.rd.server;

import com.ltm.rd.common.SessionLifecycle;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An accepted socket that has not completed its handshake. Exactly one of two things happens to
 * it: a session takes it over ({@link #handOver}) or the handshake side closes it
 * ({@link #claimForClose}); a CAS decides, so neither can act on a socket the other owns.
 */
public final class PendingConnection {
    private enum State { PENDING, HANDED_OVER, CLOSED }

    private final Socket socket;
    private final long acceptedAtNanos;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public PendingConnection(Socket socket, long acceptedAtNanos) {
        this.socket = Objects.requireNonNull(socket);
        this.acceptedAtNanos = acceptedAtNanos;
    }

    public Socket socket() {
        return socket;
    }

    public long acceptedAtNanos() {
        return acceptedAtNanos;
    }

    /** True for the single caller that may now close the socket; false if handed over or claimed. */
    public boolean claimForClose() {
        return state.compareAndSet(State.PENDING, State.CLOSED);
    }

    /** Claims the connection and closes its raw socket, unless a session already owns it. */
    public void closeIfPending() {
        if (!claimForClose()) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    /**
     * Must be called inside the receiving session's transaction. Refused once
     * {@code now >= acceptedAt + deadline}, if the session is closed, or if the connection was
     * already claimed or handed over. On success the socket is registered with the session, so
     * the session closes it from then on. A refusal leaves the connection claimable.
     */
    public boolean handOver(long now, Duration deadline, SessionLifecycle.Tx<?> tx) {
        if (now - (acceptedAtNanos + deadline.toNanos()) >= 0) return false;
        if (tx.isClosed()) return false;
        if (!state.compareAndSet(State.PENDING, State.HANDED_OVER)) return false;
        tx.register(socket);
        return true;
    }
}
