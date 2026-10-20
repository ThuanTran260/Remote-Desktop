package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.awaitTrue;
import static com.ltm.rd.ui.SnapshotPumpTest.snapshotWithFrames;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class SnapshotApplierTest {

    @Test
    void slowEdtAppliesOnlyTheLatest() {
        SnapshotPumpTest.CountingController controller =
                new SnapshotPumpTest.CountingController(n -> snapshotWithFrames(n));
        SnapshotPump pump = new SnapshotPump(controller);
        List<Snapshot> applied = new ArrayList<>();
        SnapshotApplier applier = new SnapshotApplier(pump, applied::add);
        for (int i = 0; i < 1000; i++) pump.pollOnce(); // the EDT never ticked meanwhile
        applier.tick();
        assertEquals(1, applied.size());
        assertEquals(1000, applied.get(0).frameCount());
    }

    @Test
    void unchangedReferenceIsNotReapplied() {
        SnapshotPumpTest.CountingController controller =
                new SnapshotPumpTest.CountingController(n -> snapshotWithFrames(1));
        SnapshotPump pump = new SnapshotPump(controller);
        List<Snapshot> applied = new ArrayList<>();
        SnapshotApplier applier = new SnapshotApplier(pump, applied::add);
        applier.tick();
        assertTrue(applied.isEmpty(), "nothing polled yet");
        pump.pollOnce();
        applier.tick();
        applier.tick();
        assertEquals(1, applied.size());
        pump.pollOnce(); // a new object, equal in content: still a change of reference
        applier.tick();
        assertEquals(2, applied.size());
    }

    @Test
    void applierHoldsNoBacklog() throws Exception {
        List<WeakReference<Snapshot>> first = new ArrayList<>();
        SnapshotPumpTest.CountingController controller = new SnapshotPumpTest.CountingController(n -> {
            Snapshot s = snapshotWithFrames(n);
            if (n == 1) first.add(new WeakReference<>(s));
            return s;
        });
        SnapshotPump pump = new SnapshotPump(controller);
        List<Long> applied = new ArrayList<>();
        SnapshotApplier applier = new SnapshotApplier(pump, s -> applied.add(s.frameCount()));
        pump.pollOnce();
        applier.tick(); // the first snapshot is applied, then replaced by later ones
        for (int i = 0; i < 10_000; i++) pump.pollOnce();
        applier.tick();
        assertEquals(List.of(1L, 10_001L), applied);
        WeakReference<Snapshot> ref = first.get(0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L); // gives the collector time; the assertion is on the reference
        }
        assertNull(ref.get(), "only the latest snapshot is retained");
    }

    @Test
    void timerTicksOnTheEdt() throws Exception {
        SnapshotPumpTest.CountingController controller =
                new SnapshotPumpTest.CountingController(n -> snapshotWithFrames(n));
        SnapshotPump pump = new SnapshotPump(controller);
        pump.pollOnce();
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();
        SnapshotTimer timer = new SnapshotTimer(new SnapshotApplier(pump, s -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            applied.countDown();
        }), 5);
        timer.start();
        try {
            assertTrue(applied.await(10, TimeUnit.SECONDS));
            assertTrue(onEdt.get());
        } finally {
            timer.stop();
        }
    }
}
