package com.ltm.rd.common;

import java.time.Duration;
import java.util.Objects;

/** Declares a write stuck once it exceeds the limit and reports it, outside any lock, once. */
public final class WriteWatchdog implements Tickable {
    private final WriteTracker tracker;
    private final Duration limit;
    private final Runnable onStuck;

    public WriteWatchdog(WriteTracker tracker, Duration limit, Runnable onStuck) {
        this.tracker = Objects.requireNonNull(tracker);
        this.limit = Objects.requireNonNull(limit);
        this.onStuck = Objects.requireNonNull(onStuck);
    }

    @Override
    public void tick(long nowNanos) {
        if (tracker.tryExpire(nowNanos, limit)) onStuck.run();
    }
}
