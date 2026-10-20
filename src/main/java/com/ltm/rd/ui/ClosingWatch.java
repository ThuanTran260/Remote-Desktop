package com.ltm.rd.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Tells the UI when a session has been CLOSING for long enough to explain the wait to the user. */
public final class ClosingWatch {
    static final Duration WARN_AFTER = Duration.ofSeconds(2);

    private final LongSupplier nanoClock;
    private boolean closing;
    private long since;

    public ClosingWatch(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    /** True once {@code s} has been CLOSING for at least two seconds; leaving CLOSING resets the count. */
    public boolean warning(Snapshot s) {
        long now = nanoClock.getAsLong();
        if (s.phase() != UiPhase.CLOSING) {
            closing = false;
            return false;
        }
        if (!closing) {
            closing = true;
            since = now;
        }
        return now - since >= WARN_AFTER.toNanos();
    }
}
