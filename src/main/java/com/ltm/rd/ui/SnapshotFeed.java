package com.ltm.rd.ui;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * The pump, the applier and the EDT timer of one window, started and stopped together. The window
 * gets only the latest snapshot, on the EDT, at most every 50 ms.
 */
final class SnapshotFeed implements AutoCloseable {
    static final int PERIOD_MS = 50;

    private final SnapshotPump pump;
    private final SnapshotTimer timer;

    SnapshotFeed(Controller controller, Consumer<Snapshot> onSnapshot) {
        pump = new SnapshotPump(controller, Duration.ofMillis(PERIOD_MS));
        timer = new SnapshotTimer(new SnapshotApplier(pump, onSnapshot), PERIOD_MS);
    }

    /** EDT. */
    void start() {
        timer.start();
    }

    /** The newest snapshot or null; for the statistics timer. */
    Snapshot latest() {
        return pump.latest();
    }

    /** EDT-safe: neither call waits for the pump thread. */
    @Override
    public void close() {
        timer.stop();
        pump.close();
    }
}
