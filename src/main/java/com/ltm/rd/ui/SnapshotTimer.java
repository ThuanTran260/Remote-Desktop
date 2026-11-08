package com.ltm.rd.ui;

import java.util.Objects;
import javax.swing.Timer;

/** A Swing timer that runs {@link SnapshotApplier#tick()} on the EDT: one timer, never one runnable per frame. */
public final class SnapshotTimer {
    private final Timer timer;

    public SnapshotTimer(SnapshotApplier applier, int periodMs) {
        Objects.requireNonNull(applier);
        timer = new Timer(periodMs, e -> applier.tick());
        timer.setCoalesce(true); // late ticks merge instead of piling up
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
    }
}
