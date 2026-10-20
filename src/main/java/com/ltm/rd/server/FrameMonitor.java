package com.ltm.rd.server;

import java.time.Duration;

/**
 * Pure frame-liveness logic: no lock, no listener, no clock of its own. It must only be called
 * inside the session transaction, whose lock protects it; the caller handles each returned
 * {@link Transition} in that same transaction. Every state change is returned exactly once and
 * bumps {@link #revision()}.
 */
public final class FrameMonitor {
    public enum Transition { NONE, STALL_START, STALL_END, TIMEOUT }

    private final long stallNanos;
    private final long timeoutNanos;
    private long lastFrameAt;
    private boolean hasFrame;
    private boolean stalled;
    private boolean timedOut;
    private long revision;

    /** {@code lastFrameAt} starts at {@code activeAtNanos}, so stall and timeout count from ACTIVE. */
    public FrameMonitor(long activeAtNanos, Duration stall, Duration timeout) {
        this.lastFrameAt = activeAtNanos;
        this.stallNanos = stall.toNanos();
        this.timeoutNanos = timeout.toNanos();
    }

    /** A frame arrived. Once timed out the frame is dropped and TIMEOUT is returned again. */
    public Transition onFrame(long now) {
        if (timedOut) return Transition.TIMEOUT;
        lastFrameAt = now;
        hasFrame = true;
        if (stalled) {
            stalled = false;
            revision++;
            return Transition.STALL_END;
        }
        return Transition.NONE;
    }

    /** Periodic check; reports a stall start or the timeout once each. */
    public Transition onTick(long now) {
        if (timedOut) return Transition.NONE;
        long sinceFrame = now - lastFrameAt;
        if (sinceFrame >= timeoutNanos) {
            timedOut = true;
            revision++;
            return Transition.TIMEOUT;
        }
        if (!stalled && sinceFrame >= stallNanos) {
            stalled = true;
            revision++;
            return Transition.STALL_START;
        }
        return Transition.NONE;
    }

    public boolean hasFrame() {
        return hasFrame;
    }

    public boolean stalled() {
        return stalled;
    }

    public boolean timedOut() {
        return timedOut;
    }

    /** Incremented on every transition, so observers can discard notifications that arrive late. */
    public long revision() {
        return revision;
    }
}
