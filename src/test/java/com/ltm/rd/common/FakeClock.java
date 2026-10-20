package com.ltm.rd.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Manually advanced nano clock for deterministic deadline tests. */
public final class FakeClock implements LongSupplier {
    private final AtomicLong now = new AtomicLong();

    @Override
    public long getAsLong() {
        return now.get();
    }

    public void set(long nanos) {
        now.set(nanos);
    }

    public void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    /** Seconds to nanos, for readable test timelines. */
    public static long s(long seconds) {
        return Duration.ofSeconds(seconds).toNanos();
    }
}
