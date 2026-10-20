package com.ltm.rd.common;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class WriteTrackerTest {
    private static final Duration FIVE = Duration.ofSeconds(5);
    private static final int WAIT_S = 10;

    @Test
    void trackerExpiresOnlyAtLimit() {
        WriteTracker t = new WriteTracker();
        t.begin(0);
        assertFalse(t.tryExpire(s(5) - 1, FIVE));
        assertTrue(t.tryExpire(s(5), FIVE));
        assertFalse(t.tryExpire(s(6), FIVE));
    }

    @Test
    void endAfterExpireReturnsFalse() {
        WriteTracker t = new WriteTracker();
        long op = t.begin(0);
        assertTrue(t.tryExpire(s(5), FIVE));
        assertFalse(t.end(op));
    }

    @Test
    void expireAfterEndReturnsFalse() {
        WriteTracker t = new WriteTracker();
        long op = t.begin(0);
        assertTrue(t.end(op));
        assertFalse(t.tryExpire(s(60), FIVE));
        assertFalse(t.isExpired());
    }

    @Test
    void endAndExpireRaceExactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            for (int i = 0; i < 10_000; i++) {
                WriteTracker t = new WriteTracker();
                long op = t.begin(0);
                Future<Boolean> ended = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return t.end(op);
                });
                Future<Boolean> expired = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return t.tryExpire(s(10), FIVE);
                });
                boolean e = ended.get(WAIT_S, TimeUnit.SECONDS);
                boolean x = expired.get(WAIT_S, TimeUnit.SECONDS);
                assertTrue(e ^ x, "iteration " + i + ": end=" + e + " expire=" + x);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void beginAfterExpireReturnsExpiredOpAndStaysExpired() {
        WriteTracker t = new WriteTracker();
        t.begin(0);
        assertTrue(t.tryExpire(s(5), FIVE));
        assertEquals(WriteTracker.EXPIRED_OP, t.begin(s(6)));
        assertFalse(t.end(1));
        assertFalse(t.end(WriteTracker.EXPIRED_OP));
        assertTrue(t.isExpired());
        assertFalse(t.tryExpire(s(60), FIVE));
        assertEquals(WriteTracker.EXPIRED_OP, t.begin(s(61)));
    }

    @Test
    void beginWhileWritingThrows() {
        WriteTracker t = new WriteTracker();
        t.begin(0);
        assertThrows(IllegalStateException.class, () -> t.begin(1));
    }

    @Test
    void viewsFollowTheOperation() {
        WriteTracker t = new WriteTracker();
        assertEquals(0, t.writeStartedAtNanos());
        assertEquals(0, t.writeOperationId());
        long op = t.begin(777);
        assertEquals(1, op);
        assertEquals(777, t.writeStartedAtNanos());
        assertEquals(1, t.writeOperationId());
        t.end(op);
        assertEquals(0, t.writeStartedAtNanos());
        assertEquals(1, t.writeOperationId());
        assertEquals(2, t.begin(0));
        assertEquals(1, t.writeStartedAtNanos(), "0 is reserved for idle");
    }

    // ---- CommandWriter integration ---------------------------------------------------------

    private static final class GatedSink extends OutputStream {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            synchronized (data) {
                data.write(b, off, len);
            }
        }

        List<Packet> packets() throws IOException {
            byte[] snapshot;
            synchronized (data) {
                snapshot = data.toByteArray();
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            List<Packet> out = new java.util.ArrayList<>();
            while (true) {
                var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, java.util.EnumSet.allOf(PacketType.class));
                if (p.isEmpty()) return out;
                out.add(p.get());
            }
        }
    }

    @Test
    void writerStopsAfterExpire() throws Exception {
        GatedSink sink = new GatedSink();
        java.util.concurrent.atomic.AtomicReference<Thread> writerThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), 256, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            writerThread.set(t);
            return t;
        }, t -> {}, System::nanoTime);
        try {
            w.start();
            w.offer(Packet.ping(1));
            assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS));
            w.offer(Packet.ping(2));
            assertEquals(List.of(Packet.ping(2)), w.queuedSnapshot(), "ping(2) is queued behind the stuck write");
            assertTrue(w.tracker().tryExpire(Long.MAX_VALUE / 2, Duration.ZERO));
            sink.gate.countDown();
            writerThread.get().join(WAIT_S * 1000L);
            assertFalse(writerThread.get().isAlive(), "writer must stop after its write expired");
            assertEquals(List.of(Packet.ping(1)), sink.packets(), "queued ping(2) must never reach the sink");
            assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(3)));
        } finally {
            w.stop();
        }
    }

    @Test
    void startWithRefusingStarterLeavesWriterClosed() {
        AtomicInteger asked = new AtomicInteger();
        CommandWriter w = new CommandWriter(new DataOutputStream(new ByteArrayOutputStream()), 256, r -> {
            throw new AssertionError("no thread may be created");
        }, t -> {}, System::nanoTime);
        assertFalse(w.start((name, body, skipped) -> {
            asked.incrementAndGet();
            assertTrue(name.startsWith("rd-cmd-writer-"), name);
            return false;
        }));
        assertEquals(1, asked.get());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(1)));
        assertFalse(w.start((name, body, skipped) -> true), "refused writer cannot be restarted");
    }

    @Test
    void startWithStarterRunsBodyAndIsIdempotent() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void flush() {
                flushed.countDown();
            }
        };
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), t -> {});
        AtomicInteger starts = new AtomicInteger();
        WorkerStarter starter = (name, body, skipped) -> {
            starts.incrementAndGet();
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            t.start();
            return true;
        };
        try {
            assertTrue(w.start(starter));
            assertFalse(w.start(starter));
            w.start();
            assertEquals(1, starts.get());
            w.offer(Packet.ping(7));
            assertTrue(flushed.await(WAIT_S, TimeUnit.SECONDS), "body ran and wrote the packet");
        } finally {
            w.stop();
        }
    }

    @Test
    void commandWriterTracksEveryWrite() throws Exception {
        CountDownLatch flushed = new CountDownLatch(3);
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void flush() {
                flushed.countDown();
            }
        };
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), t -> {});
        try {
            w.start();
            w.offer(Packet.ping(1));
            w.offer(Packet.ping(2));
            w.offer(Packet.ping(3));
            assertTrue(flushed.await(WAIT_S, TimeUnit.SECONDS));
            w.closeGracefully(null).get(WAIT_S, TimeUnit.SECONDS);
            assertEquals(3, w.writeOperationId());
            assertEquals(0, w.writeStartedAtNanos());
            assertFalse(w.tracker().isExpired());
        } finally {
            w.stop();
        }
    }
}
