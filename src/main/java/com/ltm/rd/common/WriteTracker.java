package com.ltm.rd.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic state of one writer's current operation: {@code IDLE -> WRITING -> IDLE ...}, with
 * {@code WRITING -> EXPIRED} decided by a watchdog. EXPIRED is terminal: the writer must stop
 * sending, so no write can follow a stuck-write decision.
 */
public final class WriteTracker {
    /** Returned by {@link #begin} once the tracker has expired. */
    public static final long EXPIRED_OP = -1;

    private sealed interface State permits Idle, Writing, Expired {}

    private record Idle(long lastOp) implements State {}

    private record Writing(long op, long startedAt) implements State {}

    private record Expired(long op) implements State {}

    private final AtomicReference<State> state = new AtomicReference<>(new Idle(0));

    /** Starts an operation; only valid when idle. Returns {@link #EXPIRED_OP} after expiry. */
    public long begin(long nowNanos) {
        while (true) {
            State s = state.get();
            if (s instanceof Expired) return EXPIRED_OP;
            if (s instanceof Writing) throw new IllegalStateException("write already in progress");
            long op = ((Idle) s).lastOp() + 1;
            if (state.compareAndSet(s, new Writing(op, nowNanos))) return op;
        }
    }

    /** Finishes operation {@code op}; false if it was already declared stuck. */
    public boolean end(long op) {
        State s = state.get();
        return s instanceof Writing w && w.op() == op && state.compareAndSet(s, new Idle(op));
    }

    /** Declares the current write stuck if it has run for at least {@code limit}; true at most once. */
    public boolean tryExpire(long nowNanos, Duration limit) {
        State s = state.get();
        return s instanceof Writing w
                && nowNanos - w.startedAt() >= limit.toNanos()
                && state.compareAndSet(s, new Expired(w.op()));
    }

    public boolean isExpired() {
        return state.get() instanceof Expired;
    }

    /** Start of the write in progress, or 0 when none. */
    public long writeStartedAtNanos() {
        return state.get() instanceof Writing w ? (w.startedAt() == 0 ? 1 : w.startedAt()) : 0;
    }

    /** Id of the current or most recent operation; 0 before the first one. */
    public long writeOperationId() {
        return switch (state.get()) {
            case Idle i -> i.lastOp();
            case Writing w -> w.op();
            case Expired e -> e.op();
        };
    }
}
