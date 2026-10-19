package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CommandWriterTest {
    private static final int WAIT_S = 10;
    private final List<CommandWriter> writers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (CommandWriter w : writers) w.stop();
    }

    /** Controllable sink: records bytes, optionally blocks in write or flush, or fails. */
    private static class FakeSink extends OutputStream {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch flushEntered = new CountDownLatch(1);
        final AtomicInteger flushes = new AtomicInteger();
        volatile CountDownLatch blockWrite;
        volatile CountDownLatch blockFlush;
        volatile IOException failWith;
        volatile RuntimeException failRuntime;
        volatile CountDownLatch flushCounter;

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writeEntered.countDown();
            await(blockWrite);
            if (failRuntime != null) throw failRuntime;
            if (failWith != null) throw failWith;
            synchronized (data) {
                data.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            flushEntered.countDown();
            await(blockFlush);
            flushes.incrementAndGet();
            CountDownLatch c = flushCounter;
            if (c != null) c.countDown();
        }

        private static void await(CountDownLatch l) throws IOException {
            if (l == null) return;
            try {
                l.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
        }

        List<Packet> packets() throws IOException {
            byte[] snapshot;
            synchronized (data) {
                snapshot = data.toByteArray();
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            List<Packet> out = new ArrayList<>();
            while (true) {
                var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, java.util.EnumSet.allOf(PacketType.class));
                if (p.isEmpty()) return out;
                out.add(p.get());
            }
        }
    }

    private CommandWriter writer(FakeSink sink, int capacity, java.util.function.Consumer<Throwable> onErr) {
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), capacity, Thread::new, onErr,
                System::nanoTime);
        writers.add(w);
        return w;
    }

    private CommandWriter writer(FakeSink sink) {
        return writer(sink, 256, t -> {});
    }

    // ---- queue semantics (writer not started: queue contents are inspectable) -------------

    @Test
    void coalescesLastMove() {
        CommandWriter w = writer(new FakeSink());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mouseMove(1, 1)));
        assertEquals(CommandWriter.OfferResult.COALESCED, w.offer(Packet.mouseMove(2, 2)));
        assertEquals(CommandWriter.OfferResult.COALESCED, w.offer(Packet.mouseMove(3, 3)));
        assertEquals(List.of(Packet.mouseMove(3, 3)), w.queuedSnapshot());
    }

    @Test
    void doesNotCoalesceAcrossPress() {
        CommandWriter w = writer(new FakeSink());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mouseMove(1, 1)));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mousePress(1, 1, 1)));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mouseMove(2, 2)));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mouseRelease(2, 2, 1)));
        assertEquals(List.of(Packet.mouseMove(1, 1), Packet.mousePress(1, 1, 1), Packet.mouseMove(2, 2),
                Packet.mouseRelease(2, 2, 1)), w.queuedSnapshot());
    }

    @Test
    void doesNotCoalesceAcrossKeyOrOtherPackets() {
        CommandWriter w = writer(new FakeSink());
        w.offer(Packet.mouseMove(1, 1));
        w.offer(Packet.keyPress(65));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.mouseMove(2, 2)));
        assertEquals(3, w.queuedSnapshot().size());
    }

    @Test
    void overflowAtCapacityWithoutCallback() {
        AtomicInteger callbacks = new AtomicInteger();
        CommandWriter w = writer(new FakeSink(), 2, t -> callbacks.incrementAndGet());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.ping(1)));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, w.offer(Packet.ping(2)));
        assertEquals(CommandWriter.OfferResult.OVERFLOW, w.offer(Packet.ping(3)));
        assertEquals(List.of(Packet.ping(1), Packet.ping(2)), w.queuedSnapshot());
        assertEquals(0, callbacks.get());
    }

    @Test
    void coalescingStillWorksWhenFull() {
        CommandWriter w = writer(new FakeSink(), 2, t -> {});
        w.offer(Packet.mousePress(1, 1, 1));
        w.offer(Packet.mouseMove(1, 1));
        assertEquals(CommandWriter.OfferResult.COALESCED, w.offer(Packet.mouseMove(9, 9)));
        assertEquals(CommandWriter.OfferResult.OVERFLOW, w.offer(Packet.ping(1)));
    }

    @Test
    void offerAfterCloseIsClosedAndQueueDiscarded() throws Exception {
        CommandWriter w = writer(new FakeSink());
        w.offer(Packet.ping(1));
        w.offer(Packet.ping(2));
        CompletableFuture<Void> done = w.closeGracefully(null);
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(3)));
        assertTrue(w.queuedSnapshot().isEmpty());
        done.get(WAIT_S, TimeUnit.SECONDS); // never started and nothing in flight: completes at once
    }

    // ---- writer thread ---------------------------------------------------------------------

    @Test
    void writesAndFlushesEveryPacketInOrder() throws Exception {
        FakeSink sink = new FakeSink();
        sink.flushCounter = new CountDownLatch(3);
        CommandWriter w = writer(sink);
        w.start();
        w.offer(Packet.ping(1));
        w.offer(Packet.mousePress(1, 2, 1));
        w.offer(Packet.pong(3));
        assertTrue(sink.flushCounter.await(WAIT_S, TimeUnit.SECONDS));
        assertEquals(List.of(Packet.ping(1), Packet.mousePress(1, 2, 1), Packet.pong(3)), sink.packets());
        assertEquals(3, sink.flushes.get());
    }

    @Test
    void inFlightPacketIsWrittenBeforeFinalAndQueuedAreDiscarded() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockWrite = new CountDownLatch(1);
        CommandWriter w = writer(sink);
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.writeEntered.await(WAIT_S, TimeUnit.SECONDS));
        w.offer(Packet.ping(2));
        w.offer(Packet.ping(3));
        CompletableFuture<Void> done = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
        assertFalse(done.isDone());
        sink.blockWrite.countDown();
        done.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(List.of(Packet.ping(1), Packet.disconnect(Packet.DisconnectReason.USER, "bye")),
                sink.packets());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(4)));
    }

    @Test
    void repeatedCloseReturnsSameFuture() {
        CommandWriter w = writer(new FakeSink());
        w.start();
        CompletableFuture<Void> a = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "x"));
        CompletableFuture<Void> b = w.closeGracefully(null);
        CompletableFuture<Void> c = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.TIMEOUT, "y"));
        assertSame(a, b);
        assertSame(a, c);
    }

    @Test
    void secondCloseDoesNotReplaceFinalPacket() throws Exception {
        FakeSink sink = new FakeSink();
        CommandWriter w = writer(sink);
        w.start();
        CompletableFuture<Void> f = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "first"));
        w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.TIMEOUT, "second"));
        f.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(List.of(Packet.disconnect(Packet.DisconnectReason.USER, "first")), sink.packets());
    }

    @Test
    void futureNotCompletedWhileFlushIsBlocked() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockFlush = new CountDownLatch(1);
        CommandWriter w = writer(sink);
        w.start();
        CompletableFuture<Void> done = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
        assertTrue(sink.flushEntered.await(WAIT_S, TimeUnit.SECONDS));
        assertFalse(done.isDone(), "done must wait for flush() to return");
        sink.blockFlush.countDown();
        done.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(1, sink.flushes.get());
    }

    @Test
    void nullFinalCompletesWhenIdle() throws Exception {
        FakeSink sink = new FakeSink();
        CommandWriter w = writer(sink);
        w.start();
        w.closeGracefully(null).get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(sink.packets().isEmpty());
    }

    @Test
    void nullFinalWaitsForInFlightPacket() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockWrite = new CountDownLatch(1);
        CommandWriter w = writer(sink);
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.writeEntered.await(WAIT_S, TimeUnit.SECONDS));
        CompletableFuture<Void> done = w.closeGracefully(null);
        assertFalse(done.isDone());
        sink.blockWrite.countDown();
        done.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(List.of(Packet.ping(1)), sink.packets());
    }

    @Test
    void writeErrorCompletesFutureExceptionallyAndCallsHandlerOnce() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockWrite = new CountDownLatch(1);
        sink.failWith = new IOException("boom");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CommandWriter w = writer(sink, 256, t -> {
            calls.incrementAndGet();
            seen.set(t);
        });
        w.start();
        w.offer(Packet.ping(1));
        w.offer(Packet.ping(2));
        assertTrue(sink.writeEntered.await(WAIT_S, TimeUnit.SECONDS));
        CompletableFuture<Void> done = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "x"));
        sink.blockWrite.countDown();
        ExecutionException ex = assertThrows(ExecutionException.class, () -> done.get(WAIT_S, TimeUnit.SECONDS));
        assertEquals("boom", ex.getCause().getMessage());
        assertEquals(1, calls.get());
        assertEquals("boom", seen.get().getMessage());
        assertTrue(sink.packets().isEmpty());
    }

    @Test
    void runtimeExceptionInWriteIsHandledLikeIoError() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockWrite = new CountDownLatch(1);
        sink.failRuntime = new IllegalStateException("bug");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CommandWriter w = writer(sink, 256, t -> {
            calls.incrementAndGet();
            seen.set(t);
        });
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.writeEntered.await(WAIT_S, TimeUnit.SECONDS));
        CompletableFuture<Void> done = w.closeGracefully(null);
        sink.blockWrite.countDown();
        ExecutionException ex = assertThrows(ExecutionException.class, () -> done.get(WAIT_S, TimeUnit.SECONDS));
        assertSame(sink.failRuntime, ex.getCause());
        assertEquals(1, calls.get());
        assertSame(sink.failRuntime, seen.get());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(2)));
    }

    @Test
    void writeErrorWithoutCloseStillCallsHandlerOnceAndLaterCloseFails() throws Exception {
        FakeSink sink = new FakeSink();
        sink.failWith = new IOException("dead");
        CountDownLatch handled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CommandWriter w = writer(sink, 256, t -> {
            calls.incrementAndGet();
            handled.countDown();
        });
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(handled.await(WAIT_S, TimeUnit.SECONDS));
        CompletableFuture<Void> done = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "x"));
        assertThrows(ExecutionException.class, () -> done.get(WAIT_S, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(2)));
    }

    @Test
    void errorHandlerRunsOutsideTheQueueLock() throws Exception {
        FakeSink sink = new FakeSink();
        sink.failWith = new IOException("dead");
        AtomicReference<CommandWriter.OfferResult> fromOtherThread = new AtomicReference<>();
        AtomicReference<CommandWriter> ref = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        CommandWriter w = writer(sink, 256, t -> {
            // a different thread offering would deadlock if the handler ran under the queue lock
            Thread other = new Thread(() -> fromOtherThread.set(ref.get().offer(Packet.ping(9))));
            other.start();
            try {
                other.join(WAIT_S * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handled.countDown();
        });
        ref.set(w);
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(handled.await(WAIT_S + 5, TimeUnit.SECONDS));
        assertNotNull(fromOtherThread.get(), "offer from another thread must not block on the handler");
    }

    @Test
    void stopIsIdempotentAndFailsPendingCloseFuture() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockFlush = new CountDownLatch(1);
        AtomicInteger threads = new AtomicInteger();
        List<Thread> created = new ArrayList<>();
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), 256, r -> {
            Thread t = new Thread(r);
            created.add(t);
            threads.incrementAndGet();
            return t;
        }, t -> {}, System::nanoTime);
        w.start();
        CompletableFuture<Void> done = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "x"));
        assertTrue(sink.flushEntered.await(WAIT_S, TimeUnit.SECONDS));
        w.stop();
        w.stop();
        assertThrows(ExecutionException.class, () -> done.get(WAIT_S, TimeUnit.SECONDS));
        created.get(0).join(WAIT_S * 1000L);
        assertFalse(created.get(0).isAlive());
        assertEquals(1, threads.get());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(1)));
    }

    @Test
    void defaultFactoryCreatesNamedDaemonThread() throws Exception {
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        FakeSink sink = new FakeSink() {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writerThread.compareAndSet(null, Thread.currentThread());
                super.write(b, off, len);
            }
        };
        sink.flushCounter = new CountDownLatch(1);
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), t -> {});
        writers.add(w);
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.flushCounter.await(WAIT_S, TimeUnit.SECONDS));
        assertTrue(writerThread.get().isDaemon());
        assertTrue(writerThread.get().getName().startsWith("rd-cmd-writer-"), writerThread.get().getName());
    }

    @Test
    void startIsIdempotent() {
        AtomicInteger created = new AtomicInteger();
        CommandWriter w = new CommandWriter(new DataOutputStream(new FakeSink()), 256, r -> {
            created.incrementAndGet();
            return new Thread(r);
        }, t -> {}, System::nanoTime);
        writers.add(w);
        w.start();
        w.start();
        assertEquals(1, created.get());
    }

    @Test
    void publishesWriteStartAndOperationIdForWatchdog() throws Exception {
        FakeSink sink = new FakeSink();
        sink.blockWrite = new CountDownLatch(1);
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), 256, Thread::new, t -> {}, () -> 12345L);
        writers.add(w);
        assertEquals(0, w.writeStartedAtNanos());
        assertEquals(0, w.writeOperationId());
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.writeEntered.await(WAIT_S, TimeUnit.SECONDS));
        assertEquals(12345L, w.writeStartedAtNanos());
        assertEquals(1, w.writeOperationId());
        CompletableFuture<Void> done = w.closeGracefully(null);
        sink.blockWrite.countDown();
        done.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, w.writeStartedAtNanos(), "idle writer reports 0");
        assertEquals(1, w.writeOperationId());
    }

    @Test
    void operationIdIncrementsPerPacketIncludingFinal() throws Exception {
        FakeSink sink = new FakeSink();
        sink.flushCounter = new CountDownLatch(1);
        CommandWriter w = writer(sink);
        w.start();
        w.offer(Packet.ping(1));
        assertTrue(sink.flushCounter.await(WAIT_S, TimeUnit.SECONDS));
        w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "x")).get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(2, w.writeOperationId());
    }
}
