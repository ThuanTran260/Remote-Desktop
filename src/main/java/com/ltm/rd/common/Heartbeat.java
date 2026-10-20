package com.ltm.rd.common;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Sends PING every interval and decides a liveness timeout when nothing was received for the
 * liveness period. The timeout decision and packet acceptance are atomic under a private lock;
 * the ping offer and {@code onTimeout} run after it is released.
 */
public final class Heartbeat implements Tickable {
    private final CommandWriter writer;
    private final long intervalNanos;
    private final long livenessNanos;
    private final Runnable onTimeout;

    private final Object lock = new Object();
    private long lastReceivedNanos; // guarded by lock
    private long nextPingNanos; // guarded by lock
    private int pingSeq; // guarded by lock
    private int outstandingSeq; // guarded by lock; 0 = none
    private long outstandingSentNanos; // guarded by lock
    private long lastRttNanos = -1; // guarded by lock
    private boolean expired; // guarded by lock

    public Heartbeat(CommandWriter writer, Duration interval, Duration liveness, long startNanos,
            Runnable onTimeout) {
        this.writer = Objects.requireNonNull(writer);
        this.intervalNanos = interval.toNanos();
        this.livenessNanos = liveness.toNanos();
        this.onTimeout = Objects.requireNonNull(onTimeout);
        this.lastReceivedNanos = startNanos;
        this.nextPingNanos = startNanos + intervalNanos;
    }

    /** Records any received packet; false if a timeout was already decided. */
    public boolean onPacketReceived(long nowNanos) {
        synchronized (lock) {
            if (expired) return false;
            lastReceivedNanos = nowNanos;
            return true;
        }
    }

    /** Computes RTT when {@code seq} matches the outstanding ping; other values are ignored. */
    public void onPong(int seq, long nowNanos) {
        synchronized (lock) {
            if (outstandingSeq == 0 || seq != outstandingSeq) return;
            lastRttNanos = nowNanos - outstandingSentNanos;
            outstandingSeq = 0;
        }
    }

    public OptionalLong lastRttNanos() {
        synchronized (lock) {
            return lastRttNanos < 0 ? OptionalLong.empty() : OptionalLong.of(lastRttNanos);
        }
    }

    @Override
    public void tick(long nowNanos) {
        boolean timedOut = false;
        int pingToSend = 0;
        synchronized (lock) {
            if (expired) return;
            if (nowNanos - lastReceivedNanos >= livenessNanos) {
                expired = true;
                timedOut = true;
            } else if (nowNanos - nextPingNanos >= 0) {
                pingToSend = ++pingSeq;
                outstandingSeq = pingToSend;
                outstandingSentNanos = nowNanos;
                nextPingNanos = nowNanos + intervalNanos;
            }
        }
        if (timedOut) onTimeout.run();
        else if (pingToSend != 0) writer.offer(Packet.ping(pingToSend));
    }
}
