package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.common.Packet;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class SnapshotPumpTest {

    static Snapshot snapshotWithFrames(long frames) {
        return new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, 1, 0, "", null, 0, 0, null, 0, 0, null, frames,
                false, TrafficStats.ZERO);
    }

    /** A controller whose snapshot() numbers its answers 1, 2, 3 ... and can run a hook per call. */
    static final class CountingController implements Controller {
        final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<Snapshot> source;
        volatile Runnable beforeAnswer = () -> { };

        CountingController(IntFunction<Snapshot> source) {
            this.source = source;
        }

        @Override
        public Snapshot snapshot() {
            int n = calls.incrementAndGet();
            beforeAnswer.run();
            return source.apply(n);
        }

        @Override
        public CompletableFuture<Void> startServer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean regenerateCode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConnectOutcome connect(String host, String code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean disconnect() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SendResult sendInput(long generation, long inputEpoch, Packet inputPacket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean releaseControlInput(long generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ShutdownReport> shutdownAsync() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void pumpKeepsOnlyLatestSnapshot() {
        CountingController controller = new CountingController(n -> snapshotWithFrames(Math.min(n, 1000)));
        SnapshotPump pump = new SnapshotPump(controller);
        assertNull(pump.latest(), "nothing before the first poll");
        for (int i = 0; i < 1000; i++) pump.pollOnce();
        assertEquals(1000, pump.latest().frameCount());
        assertEquals(1000, pump.polls());
    }

    @Test
    void runningPumpPublishesNewestAnswer() throws Exception {
        CountingController controller = new CountingController(n -> snapshotWithFrames(Math.min(n, 1000)));
        try (SnapshotPump pump = new SnapshotPump(controller, Duration.ofMillis(1))) {
            awaitTrue("newest snapshot", () -> pump.latest() != null && pump.latest().frameCount() == 1000);
            long polls = pump.polls();
            assertTrue(polls >= 1000);
        }
    }

    @Test
    void pumpThreadIsNamedRdSnapshotAndStopsOnClose() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> threadName = new java.util.concurrent.atomic.AtomicReference<>();
        CountingController controller = new CountingController(n -> snapshotWithFrames(n));
        controller.beforeAnswer = () -> {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            polled.countDown();
        };
        SnapshotPump pump = new SnapshotPump(controller, Duration.ofMillis(5));
        assertTrue(polled.await(10, TimeUnit.SECONDS));
        assertEquals("rd-snapshot", threadName.get());
        assertTrue(RuntimeTestSupport.liveRdThreads().stream().anyMatch(t -> t.getName().equals("rd-snapshot")));
        pump.close();
        awaitTrue("rd-snapshot thread gone",
                () -> RuntimeTestSupport.liveRdThreads().stream().noneMatch(t -> t.getName().equals("rd-snapshot")));
    }

    @Test
    void slowControllerDoesNotQueuePolls() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountingController controller = new CountingController(n -> snapshotWithFrames(n));
        controller.beforeAnswer = () -> {
            if (controller.calls.get() == 2) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (SnapshotPump pump = new SnapshotPump(controller, Duration.ofMillis(1))) {
            assertTrue(entered.await(10, TimeUnit.SECONDS)); // the second call is parked
            long during = pump.polls();
            assertEquals(1, during, "only the first poll completed");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); // many periods pass
            assertEquals(during, pump.polls(), "no poll ran or queued while the controller was slow");
            assertEquals(2, controller.calls.get());
            release.countDown();
            awaitTrue("polling resumes", () -> pump.polls() > during + 1);
        } finally {
            release.countDown();
        }
    }
}
