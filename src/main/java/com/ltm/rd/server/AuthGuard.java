package com.ltm.rd.server;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-IP failed-code tracking. An unlocked entry expires at {@code lastFailureAt + window}; a
 * locked one at {@code lockedUntil}. The table is bounded: when full, expired entries are evicted
 * first and, if it is still full, unknown IPs are treated as locked (fail closed).
 */
public final class AuthGuard {
    private static final class Entry {
        int failures;
        long lastFailureAt;
        boolean locked;
        long lockedUntil;
    }

    private final int maxEntries;
    private final int failuresBeforeLock;
    private final long windowNanos;

    private final Object lock = new Object();
    private final Map<InetAddress, Entry> entries = new HashMap<>(); // guarded by lock

    public AuthGuard(int maxEntries, int failuresBeforeLock, Duration window) {
        if (maxEntries < 1 || failuresBeforeLock < 1) throw new IllegalArgumentException("limits must be >= 1");
        this.maxEntries = maxEntries;
        this.failuresBeforeLock = failuresBeforeLock;
        this.windowNanos = Objects.requireNonNull(window).toNanos();
    }

    public boolean isLocked(InetAddress ip, long now) {
        Objects.requireNonNull(ip);
        synchronized (lock) {
            Entry e = entries.get(ip);
            if (e == null) return entries.size() >= maxEntries && !makeRoom(now);
            if (expired(e, now)) {
                entries.remove(ip);
                return false;
            }
            return e.locked;
        }
    }

    public void recordFailure(InetAddress ip, long now) {
        Objects.requireNonNull(ip);
        synchronized (lock) {
            Entry e = entries.get(ip);
            if (e != null && expired(e, now)) {
                entries.remove(ip);
                e = null;
            }
            if (e == null) {
                if (entries.size() >= maxEntries && !makeRoom(now)) return; // such IPs already count as locked
                e = new Entry();
                entries.put(ip, e);
            }
            e.failures++;
            e.lastFailureAt = now;
            if (!e.locked && e.failures >= failuresBeforeLock) {
                e.locked = true;
                e.lockedUntil = now + windowNanos;
            }
        }
    }

    public void recordSuccess(InetAddress ip) {
        Objects.requireNonNull(ip);
        synchronized (lock) {
            entries.remove(ip);
        }
    }

    /** Number of tracked IPs, for tests. */
    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** Caller holds the lock. Evicts expired entries; true if there is room for one more. */
    private boolean makeRoom(long now) {
        entries.values().removeIf(e -> expired(e, now));
        return entries.size() < maxEntries;
    }

    private boolean expired(Entry e, long now) {
        return e.locked ? now - e.lockedUntil >= 0 : now - (e.lastFailureAt + windowNanos) >= 0;
    }
}
