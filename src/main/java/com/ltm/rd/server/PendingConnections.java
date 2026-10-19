package com.ltm.rd.server;

import com.ltm.rd.common.Tickable;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks connections still in their handshake and closes those that outlive the deadline. */
public final class PendingConnections implements Tickable {
    private final long deadlineNanos;
    private final Set<PendingConnection> connections = ConcurrentHashMap.newKeySet();

    public PendingConnections(Duration handshakeDeadline) {
        this.deadlineNanos = Objects.requireNonNull(handshakeDeadline).toNanos();
    }

    public void add(PendingConnection pc) {
        connections.add(Objects.requireNonNull(pc));
    }

    public void remove(PendingConnection pc) {
        connections.remove(pc);
    }

    /** Closes connections whose absolute deadline has passed; never before it. */
    @Override
    public void tick(long nowNanos) {
        for (PendingConnection pc : connections) {
            if (nowNanos - (pc.acceptedAtNanos() + deadlineNanos) >= 0) {
                pc.closeIfPending();
                connections.remove(pc);
            }
        }
    }

    /** Closes every connection that is still pending, e.g. on shutdown. */
    public void closeAll() {
        for (PendingConnection pc : connections) {
            pc.closeIfPending();
            connections.remove(pc);
        }
    }
}
