package com.ltm.rd.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Samples {@link Controller#snapshot()} on the {@code rd-snapshot} thread and keeps only the
 * latest result. There is no queue: a slow controller delays the next poll (fixed delay), a slow
 * reader simply sees the newest value.
 */
public final class SnapshotPump implements AutoCloseable {
    private final Controller controller;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>();
    private final AtomicLong polls = new AtomicLong();
    private final ScheduledExecutorService executor; // null for a manual pump (tests)

    public SnapshotPump(Controller controller, Duration period) {
        this.controller = Objects.requireNonNull(controller);
        long periodNanos = period.toNanos();
        if (periodNanos <= 0) throw new IllegalArgumentException("period must be positive");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rd-snapshot");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pollOnce, 0, periodNanos, TimeUnit.NANOSECONDS);
    }

    /** A pump without a thread; the test calls {@link #pollOnce()} itself. */
    SnapshotPump(Controller controller) {
        this.controller = Objects.requireNonNull(controller);
        this.executor = null;
    }

    void pollOnce() {
        Snapshot s = controller.snapshot();
        latest.set(s);
        polls.incrementAndGet();
    }

    /** The newest snapshot, or null before the first poll finished. Lock-free. */
    public Snapshot latest() {
        return latest.get();
    }

    /** Completed polls, for tests. */
    public long polls() {
        return polls.get();
    }

    /** Stops the thread without waiting for it, so it is safe to call from the EDT. */
    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }
}
