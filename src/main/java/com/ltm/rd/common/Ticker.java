package com.ltm.rd.common;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Drives registered {@link Tickable}s from one daemon thread. The period is nominal: a tick can
 * be late but never early. A tickable that throws is logged and does not affect the others or
 * later ticks.
 */
public final class Ticker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Ticker.class.getName());

    /** Handle returned by {@link #register}; closing it unregisters the tickable. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final LongSupplier nanoClock;
    private final ScheduledExecutorService executor;
    private final CopyOnWriteArrayList<Tickable> tickables = new CopyOnWriteArrayList<>();

    public Ticker(Duration period, LongSupplier nanoClock) {
        Objects.requireNonNull(period);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        long periodNanos = period.toNanos();
        if (periodNanos <= 0) throw new IllegalArgumentException("period must be positive");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rd-ticker");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runOnce, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    /** Registers a tickable; each registration is independent even for the same instance. */
    public Registration register(Tickable t) {
        Objects.requireNonNull(t);
        Tickable entry = t::tick; // unique identity per registration
        tickables.add(entry);
        return () -> tickables.remove(entry);
    }

    /** Number of currently registered tickables, for leak checks in tests. */
    public int registrations() {
        return tickables.size();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runOnce() {
        long now = nanoClock.getAsLong();
        for (Tickable t : tickables) {
            try {
                t.tick(now);
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.WARNING, "tickable failed", e);
            }
        }
    }
}
