package com.ltm.rd.viewer;

import com.ltm.rd.host.PendingConnection;
import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.viewer.ViewerConfig;
import com.ltm.rd.viewer.ViewerHooks;
import com.ltm.rd.viewer.ViewerPhase;
import com.ltm.rd.viewer.ViewerSession;
import com.ltm.rd.viewer.ViewerTestAccess;
import com.ltm.rd.viewer.SocketConnector;
import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.viewer.FrameMonitor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The stall gate and the frame timeout decided inside the session transaction. The session runs
 * with a real writer thread writing to a capturing sink (frame receiver off), so the order in
 * which packets reach the wire is observable; every ordering is forced with latches.
 */
@Timeout(120)
class ViewerSessionGateTest {
    private static final int WAIT_S = 10;
    private static final byte[] TOKEN = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(2), Duration.ofSeconds(8),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(3),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "test-sched");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService cleanup = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "test-pool");
        t.setDaemon(true);
        return t;
    });
    private final List<ViewerSession> sessions = new ArrayList<>();
    private final List<Sink> sinks = new ArrayList<>();

    @AfterEach
    void shutdown() {
        for (ViewerSession s : sessions) s.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        pool.shutdownNow();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    /** Bytes become visible only on flush, so a snapshot never ends in the middle of a packet. */
    private static final class Sink extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream flushed = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            pending.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            pending.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            flushed.write(pending.toByteArray(), 0, pending.size());
            pending.reset();
        }

        synchronized byte[] snapshot() {
            return flushed.toByteArray();
        }
    }

    /** A command-channel input that blocks until closed, like an idle peer. */
    private static final class IdleInput extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** An ACTIVE session at time 0 whose command writer writes to a capturing sink. */
    private ViewerSession active() throws Exception {
        Ticker ticker = new Ticker(Duration.ofHours(1), () -> 0L);
        ViewerSession session = new ViewerSession(new ViewerConfig("127.0.0.1", 1, 2, "123456", TIMEOUTS, () -> 0L),
                1, SocketConnector.DEFAULT, ViewerHooks.none(), ticker, scheduler, cleanup);
        session.lifecycle().addStopHook(ticker::close);
        session.lifecycle().atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING));
        sessions.add(session);
        Sink sink = new Sink();
        sinks.add(sink);
        PendingConnection cmdPending = new PendingConnection(new Socket(), 0);
        Channel cmd = new Channel(cmdPending.socket(), new DataInputStream(new IdleInput()), new DataOutputStream(sink));
        session.lifecycle().register(cmd.socket());
        session.lifecycle().addStopHook(cmd::closeWrappersQuietly);
        assertTrue(ViewerTestAccess.attachCommandChannel(session, cmd));
        ViewerTestAccess.dispatch(session, Packet.screenInfo(1920, 1080));
        PendingConnection imgPending = new PendingConnection(new Socket(), 0);
        Channel img = new Channel(imgPending.socket(), new DataInputStream(new IdleInput()),
                new DataOutputStream(OutputStream.nullOutputStream()));
        session.lifecycle().register(img.socket());
        session.lifecycle().addStopHook(img::closeWrappersQuietly);
        ViewerTestAccess.attachImageChannel(session, img);
        ViewerTestAccess.dispatch(session, Packet.sessionActive());
        ViewerTestAccess.dispatch(session, Packet.ping(0)); // deterministic writer preamble for FIFO/overflow tests
        assertEquals(ViewerPhase.ACTIVE, session.phase());
        return session;
    }

    private static BufferedImage image() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    /** Waits (bounded) until at least {@code count} packets reached the wire and returns their types. */
    private List<PacketType> written(ViewerSession session, int count) throws Exception {
        Sink sink = sinks.get(sessions.indexOf(session));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (true) {
            List<PacketType> types = new ArrayList<>();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(sink.snapshot()));
            while (true) {
                var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class));
                if (p.isEmpty()) break;
                types.add(p.get().type());
            }
            if (types.size() >= count) return types;
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + count + " packets, have " + types);
            LockSupport.parkNanos(1_000_000);
        }
    }

    // ---- ordering of RELEASE_ALL and input -------------------------------------------------

    @Test
    void stallThenRecoveryThenInputOrdered() throws Exception {
        ViewerSession session = active();
        session.tick(s(3)); // STALL_START: the gate closes and RELEASE_ALL is queued
        assertEquals(1, session.releaseAllSent());
        assertFalse(session.controlEnabled());
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(4)); // STALL_END: the gate opens
        assertTrue(session.controlEnabled());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, session.offerInput(Packet.keyPress(5)));
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL, PacketType.KEY_PRESS),
                written(session, 3));
        assertEquals(1, session.releaseAllSent());
    }

    @Test
    void inputDuringStallDecisionNeverPrecedesReleaseAll() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1)); // the gate is open
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the stall decision is taken inside this transaction, which then stays open
        Future<?> holder = pool.submit(() -> session.lifecycle().atomically(tx -> {
            session.tick(s(4));
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertTrue(inside.await(WAIT_S, TimeUnit.SECONDS));
        Future<CommandWriter.OfferResult> input = pool.submit(() -> session.offerInput(Packet.keyPress(5)));
        assertThrows(TimeoutException.class, () -> input.get(200, TimeUnit.MILLISECONDS),
                "offerInput must wait for the transaction that closed the gate");
        release.countDown();
        holder.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(CommandWriter.OfferResult.CLOSED, input.get(WAIT_S, TimeUnit.SECONDS));
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL), written(session, 2));
        assertEquals(1, session.releaseAllSent());
    }

    @Test
    void inputBeforeStallDecisionIsFollowedByReleaseAll() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertEquals(CommandWriter.OfferResult.ACCEPTED, session.offerInput(Packet.keyPress(5)));
        session.tick(s(4));
        assertEquals(List.of(PacketType.PONG, PacketType.KEY_PRESS, PacketType.RELEASE_ALL),
                written(session, 3));
    }

    @Test
    void onlyInputPacketsAreAcceptedByOfferInput() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(Packet.ping(1)));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(Packet.releaseAll()));
    }

    @Test
    void inputBeforeTheFirstFrameIsRefused() throws Exception {
        ViewerSession session = active();
        assertFalse(session.controlEnabled());
        assertEquals(CommandWriter.OfferResult.CLOSED, session.offerInput(Packet.mouseMove(1, 1)));
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertTrue(session.controlEnabled());
        assertEquals(1, session.framesReceived());
        assertTrue(session.latestFrame().isPresent());
    }

    @Test
    void frameFromAnotherGenerationIsIgnored() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(2, image(), s(1));
        assertEquals(0, session.framesReceived());
        assertFalse(session.controlEnabled());
    }

    // ---- observers -------------------------------------------------------------------------

   @Test
    void observerDeliveryFollowsTransactionOrderEvenWhileTheFirstDeliveryIsBlocked() throws Exception {
        ViewerSession session = active();
        CountDownLatch startReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> revisions = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Boolean> stalledView = new AtomicReference<>(false);
        session.addFrameObserver((revision, t) -> {
            if (t == FrameMonitor.Transition.STALL_START) {
                startReached.countDown();
                try {
                    release.await(); // the first delivery is stuck inside the observer
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            stalledView.set(t == FrameMonitor.Transition.STALL_START);
            revisions.add(revision);
        });
        Future<?> start = pool.submit(() -> session.tick(s(3))); // transaction 1: STALL_START
        assertTrue(startReached.await(WAIT_S, TimeUnit.SECONDS));
        // transaction 2 commits while delivery 1 is still running: it must queue behind it, not overtake it
        pool.submit(() -> ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(4))).get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(revisions.isEmpty(), "nothing was delivered while the first delivery is blocked");
        release.countDown();
        start.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(List.of(1L, 2L), revisions, "delivery order equals transaction order");
        assertFalse(stalledView.get(), "the observer ends in 'not stalled'");
    }

    @Test
    void observerDeliveryOrderSurvivesRacingTransactions() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        for (int i = 0; i < 100; i++) {
            ViewerSession session = active();
            List<Long> revisions = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicReference<Boolean> stalledView = new AtomicReference<>(false);
            session.addFrameObserver((revision, t) -> {
                stalledView.set(t == FrameMonitor.Transition.STALL_START);
                revisions.add(revision);
            });
            Future<?> tick = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                session.tick(s(3));
                return null;
            });
            Future<?> frame = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(4));
                return null;
            });
            tick.get(WAIT_S, TimeUnit.SECONDS);
            frame.get(WAIT_S, TimeUnit.SECONDS);
            for (int r = 1; r < revisions.size(); r++) {
                assertTrue(revisions.get(r) > revisions.get(r - 1), "iteration " + i + ": " + revisions);
            }
            assertFalse(stalledView.get(), "iteration " + i + " ends in 'not stalled': " + revisions);
            closeAndAwait(session);
        }
    }

    private void closeAndAwait(ViewerSession session) throws Exception {
        session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        session.lifecycle().terminated().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void observerSeesTransitionsWithIncreasingRevisions() throws Exception {
        ViewerSession session = active();
        List<String> events = new ArrayList<>();
        session.addFrameObserver((revision, t) -> events.add(revision + ":" + t));
        session.tick(s(3));
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(4));
        session.tick(s(4) + TIMEOUTS.frameTimeout().toNanos());
        assertEquals(List.of("1:STALL_START", "2:STALL_END", "3:TIMEOUT"), events);
    }

    // ---- frame versus timeout --------------------------------------------------------------

    @Test
    void frameBeforeTimeoutTickIsCountedAndNothingCloses() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(15));
        session.tick(s(15));
        assertEquals(1, session.framesReceived());
        assertFalse(session.lifecycle().isClosed());
    }

    @Test
    void timeoutTickBeforeFrameClosesAndDropsTheFrame() throws Exception {
        ViewerSession session = active();
        session.tick(s(15));
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(15));
        assertEquals(0, session.framesReceived());
        CloseInfo info = session.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.TIMEOUT, info.reason());
        assertEquals("frame timeout", info.message());
    }

    @Test
    void frameVersusTimeoutRaceExactlyOne() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        for (int i = 0; i < 300; i++) {
            ViewerSession session = active();
            Future<?> frame = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(15));
                return null;
            });
            Future<?> tick = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                session.tick(s(15));
                return null;
            });
            frame.get(WAIT_S, TimeUnit.SECONDS);
            tick.get(WAIT_S, TimeUnit.SECONDS);
            boolean counted = session.framesReceived() == 1;
            boolean closed = session.lifecycle().isClosed();
            assertTrue(counted ^ closed, "iteration " + i + ": counted=" + counted + " closed=" + closed);
            closeAndAwait(session); // stop this iteration's writer thread now, not only at the end of the test
        }
    }

    @RepeatedTest(20)
    void disconnectRacingFrameNeverReopensGate() throws Exception {
        for (boolean closeFirst : new boolean[] {true, false}) {
            ViewerSession session = active();
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            CountDownLatch first = new CountDownLatch(1);
            Future<?> close = pool.submit(() -> {
                start.await(WAIT_S, TimeUnit.SECONDS);
                if (!closeFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                session.cancel();
                if (closeFirst) first.countDown();
                return null;
            });
            Future<?> frame = pool.submit(() -> {
                start.await(WAIT_S, TimeUnit.SECONDS);
                if (closeFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
                if (!closeFirst) first.countDown();
                return null;
            });
            try {
                close.get(WAIT_S, TimeUnit.SECONDS);
                frame.get(WAIT_S, TimeUnit.SECONDS);
                assertEquals(closeFirst ? 0 : 1, session.framesReceived());
                assertFalse(session.controlEnabled());
                assertEquals(0, session.inputEpoch());
                assertEquals(1, session.generation());
                assertEquals(ViewerPhase.CLOSING, session.controlView().phase());
            } finally { first.countDown(); session.cancel(); }
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        }
    }
}
