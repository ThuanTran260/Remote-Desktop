package com.ltm.rd.ui;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The logic of the UI timer, free of Swing: on each tick, hand the pump's latest snapshot to
 * {@code onChange} unless that very object was handed over before. Holds one reference, no backlog.
 */
public final class SnapshotApplier {
    private final SnapshotPump pump;
    private final Consumer<Snapshot> onChange;
    private Snapshot applied; // the timer thread only

    public SnapshotApplier(SnapshotPump pump, Consumer<Snapshot> onChange) {
        this.pump = Objects.requireNonNull(pump);
        this.onChange = Objects.requireNonNull(onChange);
    }

    public void tick() {
        Snapshot s = pump.latest();
        if (s == null || s == applied) return;
        applied = s;
        onChange.accept(s);
    }
}
