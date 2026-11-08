package com.ltm.rd.viewer;

import com.ltm.rd.host.HostSession;
import com.ltm.rd.host.HostConfig;
import com.ltm.rd.host.HostCore;
import com.ltm.rd.host.PendingConnection;
import com.ltm.rd.host.HostTestAccess;
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
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.viewer.ControlView;
import com.ltm.rd.viewer.InputOutcome;
import java.awt.Rectangle;
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
import java.security.SecureRandom;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The control view, the epoch-checked input and the release/traffic/rtt accessors of the Server
 * session. Same harness as {@link ViewerSessionGateTest}: a real writer thread writes to a
 * capturing sink, so the order of packets on the wire is observable; every ordering is forced
 * with latches and the fake clock.
 */
@Timeout(120)
class ViewerSessionControlTest {
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
    private final List<Ticker> tickers = new ArrayList<>();
    private final Ticker idleTicker = new Ticker(Duration.ofHours(1), () -> 0L);
    private final List<CountDownLatch> gates = new ArrayList<>();

    private final HostTestAccess.HostResources hostResources = new HostTestAccess.HostResources();

    @AfterEach
    void shutdown() {
        gates.forEach(CountDownLatch::countDown);
        for (ViewerSession s : sessions) s.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        tickers.forEach(Ticker::close);
        idleTicker.close();
        pool.shutdownNow();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        hostResources.close();
    }

    /** Bytes become visible only on flush; an optional gate blocks the writer inside its first write. */
    private static final class Sink extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream flushed = new ByteArrayOutputStream();
        private final CountDownLatch gate;
        final CountDownLatch entered = new CountDownLatch(1);

        Sink(CountDownLatch gate) {
            this.gate = gate;
        }

        private void block() {
            if (gate == null) return;
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void write(int b) {
            block();
            synchronized (this) {
                pending.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            block();
            synchronized (this) {
                pending.write(b, off, len);
            }
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

    private ViewerSession newSession(LongSupplier clock, Ticker ticker) {
        if (ticker == null) ticker = idleTicker;
        ViewerSession session = new ViewerSession(new ViewerConfig("127.0.0.1", 1, 2, "123456", TIMEOUTS, clock),
                1, SocketConnector.DEFAULT, ViewerHooks.none(), ticker, scheduler, cleanup);
        session.lifecycle().atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING));
        sessions.add(session);
        return session;
    }

    /** A RESERVED session whose command channel is attached (no SCREEN_INFO, no image channel). */
    private ViewerSession reserved(LongSupplier clock, Ticker ticker, Sink sink) throws Exception {
        ViewerSession session = newSession(clock, ticker);
        sinks.add(sink);
        PendingConnection cmdPending = new PendingConnection(new Socket(), 0);
        Channel cmd = new Channel(cmdPending.socket(), new DataInputStream(new IdleInput()), new DataOutputStream(sink));
        session.lifecycle().register(cmd.socket());
        session.lifecycle().addStopHook(cmd::closeWrappersQuietly);
        assertTrue(ViewerTestAccess.attachCommandChannel(session, cmd));
        return session;
    }

    private ViewerSession active() throws Exception {
        return active(() -> 0L, null, new Sink(null));
    }

    private ViewerSession active(LongSupplier clock, Ticker ticker, Sink sink) throws Exception {
        ViewerSession session = reserved(clock, ticker, sink);
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

    private List<Packet> writtenPackets(ViewerSession session, int count) throws Exception {
        Sink sink = sinks.get(sessions.indexOf(session));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (true) {
            List<Packet> packets = new ArrayList<>();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(sink.snapshot()));
            while (true) {
                var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class));
                if (p.isEmpty()) break;
                packets.add(p.get());
            }
            if (packets.size() >= count) return packets;
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + count + " packets, have " + packets.size());
            LockSupport.parkNanos(1_000_000);
        }
    }

    private List<PacketType> written(ViewerSession session, int count) throws Exception {
        return writtenPackets(session, count).stream().map(Packet::type).toList();
    }

    // ---- epoch ---------------------------------------------------------------------------------

    @Test
    void inputEpochStartsAtZeroAndTracksMonitorRevision() throws Exception {
        ViewerSession session = active();
        List<Long> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        session.addFrameObserver((revision, t) -> seen.add(revision));
        assertEquals(0, session.inputEpoch(), "before the first frame");
        session.tick(s(3)); // STALL_START
        assertEquals(1, session.inputEpoch());
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(4)); // STALL_END
        assertEquals(2, session.inputEpoch());
        assertEquals(List.of(1L, 2L), seen, "the epoch equals the revision the observer saw");
    }

    @Test
    void offerInputWithCurrentEpochIsSent() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(17)));
        List<Packet> packets = writtenPackets(session, 2);
        assertEquals(List.of(PacketType.PONG, PacketType.KEY_PRESS),
                packets.stream().map(Packet::type).toList());
        assertEquals(17, packets.get(1).keyCode());
    }

    @Test
    void offerInputDuringStallIsGateClosed() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        session.tick(s(4)); // STALL_START: epoch 1
        assertEquals(1, session.inputEpoch());
        assertEquals(InputOutcome.GATE_CLOSED, session.offerInput(1, Packet.keyPress(17)));
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL), written(session, 2));
    }

    @Test
    void offerInputWithOldEpochAfterRecoveryIsStale() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        long captured = session.inputEpoch();
        assertEquals(0, captured);
        session.tick(s(4)); // STALL_START
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(5)); // STALL_END
        assertEquals(2, session.inputEpoch());
        assertEquals(InputOutcome.STALE_EPOCH, session.offerInput(captured, Packet.keyPress(17)));
        assertEquals(InputOutcome.SENT, session.offerInput(2, Packet.keyPress(17)));
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL, PacketType.KEY_PRESS),
                written(session, 3));
    }

    @Test
    void epochCheckGateAndOfferAreOneTransaction() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1)); // gate open at epoch 0
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
        Future<InputOutcome> input = pool.submit(() -> session.offerInput(0, Packet.keyPress(17)));
        assertThrows(TimeoutException.class, () -> input.get(200, TimeUnit.MILLISECONDS),
                "offerInput must wait for the transaction that closed the gate");
        release.countDown();
        holder.get(WAIT_S, TimeUnit.SECONDS);
        InputOutcome outcome = input.get(WAIT_S, TimeUnit.SECONDS);
        assertNotEquals(InputOutcome.SENT, outcome);
        assertTrue(outcome == InputOutcome.GATE_CLOSED || outcome == InputOutcome.STALE_EPOCH, "was " + outcome);
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL), written(session, 2));
    }

    /**
     * Runs {@code offerInput(epoch, key 17)} and {@code racer} on pool threads that are both queued
     * on the session lock: the test thread holds the lock until both have started, so the two
     * transactions contend for it as closely as possible. The short pause only widens the window;
     * no result depends on it.
     */
    private InputOutcome offerRacing(ViewerSession session, long epoch, Runnable racer) throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        List<Future<?>> futures = session.lifecycle().atomically(tx -> {
            Future<?> offer = pool.submit(() -> {
                started.countDown();
                return session.offerInput(epoch, Packet.keyPress(17));
            });
            Future<?> other = pool.submit(() -> {
                started.countDown();
                racer.run();
            });
            try {
                started.await(WAIT_S, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long until = System.nanoTime() + 300_000; // spin: parking is far coarser on Windows
            while (System.nanoTime() < until) Thread.onSpinWait(); // let both reach the lock
            return List.of(offer, other);
        });
        futures.get(1).get(WAIT_S, TimeUnit.SECONDS);
        return (InputOutcome) futures.get(0).get(WAIT_S, TimeUnit.SECONDS);
    }

    /** Like {@link #written} but polls fast: the race tests below run 10_000 iterations. */
    private List<PacketType> writtenFast(ViewerSession session, int count) throws Exception {
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
            LockSupport.parkNanos(20_000);
        }
    }

    /**
     * offerInput(0, key) races the stall decision that closes the gate. Whatever interleaving wins,
     * the writer queue must agree with the outcome: SENT means the key was queued before RELEASE_ALL,
     * anything else means no key was queued at all. A gate check and an offer in separate
     * transactions would let the key slip in after RELEASE_ALL.
     */
    @Test
    void offerInputRacingStallStartNeverQueuesKeyAfterReleaseAll() throws Exception {
        int sent = 0;
        for (int i = 0; i < 10_000; i++) {
            ViewerSession session = active();
            ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1)); // gate open at epoch 0
            InputOutcome outcome = offerRacing(session, 0, () -> session.tick(s(4))); // STALL_START
            if (outcome == InputOutcome.SENT) {
                sent++;
                assertEquals(List.of(PacketType.PONG, PacketType.KEY_PRESS, PacketType.RELEASE_ALL),
                        writtenFast(session, 3), "iteration " + i);
            } else {
                assertTrue(outcome == InputOutcome.GATE_CLOSED || outcome == InputOutcome.STALE_EPOCH,
                        "iteration " + i + ": " + outcome);
                assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL), writtenFast(session, 2),
                        "iteration " + i);
            }
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        }
        assertTrue(sent > 0 && sent < 10_000, "both interleavings must occur, sent=" + sent);
    }

    /** offerInput with the epoch of the stall races the frame that ends the stall: it can never be sent. */
    @Test
    void offerInputRacingStallEndWithStalledEpochIsNeverSent() throws Exception {
        for (int i = 0; i < 10_000; i++) {
            ViewerSession session = active();
            ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
            session.tick(s(4)); // STALL_START: epoch 1, gate closed
            assertEquals(1, session.inputEpoch());
            InputOutcome outcome = offerRacing(session, 1, () -> ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(5))); // STALL_END
            assertTrue(outcome == InputOutcome.GATE_CLOSED || outcome == InputOutcome.STALE_EPOCH,
                    "iteration " + i + ": " + outcome);
            assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL), writtenFast(session, 2),
                    "iteration " + i);
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        }
    }

    @Test
    void offerInputRejectsNonInputPackets() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(0, Packet.ping(1)));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(0, Packet.releaseAll()));
    }

    // ---- release ---------------------------------------------------------------------------------

    @Test
    void releaseControlInputWorksWhileGateClosed() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        session.tick(s(4)); // STALL_START: the gate is closed and RELEASE_ALL (stall) queued
        assertFalse(session.controlEnabled());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, session.releaseControlInput());
        assertEquals(1, session.releaseAllSent());
        assertEquals(1, session.controlReleasesSent());
        assertEquals(List.of(PacketType.PONG, PacketType.RELEASE_ALL, PacketType.RELEASE_ALL),
                written(session, 3));
    }

    @Test
    void releaseControlInputBeforeActiveReturnsClosed() throws Exception {
        Sink sink = new Sink(null);
        ViewerSession session = reserved(() -> 0L, null, sink);
        assertEquals(ViewerPhase.ATTACHING, session.phase());
        assertEquals(CommandWriter.OfferResult.CLOSED, session.releaseControlInput());
        assertEquals(0, session.controlReleasesSent());
        assertEquals(0, sink.snapshot().length, "nothing was queued");
    }

    @Test
    void releaseControlInputOverflowClosesSessionWithOverflow() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        Sink sink = new Sink(gate);
        ViewerSession session = active(() -> 0L, null, sink);
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS), "the writer is stuck inside its first write");
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) {
            assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(i + 1)), "fill " + i);
        }
        assertEquals(CommandWriter.OfferResult.OVERFLOW, session.releaseControlInput());
        assertEquals(Packet.DisconnectReason.OVERFLOW, session.lifecycle().closeInfo().orElseThrow().reason());
        assertEquals(0, session.controlReleasesSent());
    }

    // ---- control view ----------------------------------------------------------------------------

    @Test
    void controlViewIsOneConsistentSnapshot() throws Exception {
        ViewerSession session = active();
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        ControlView view = session.controlView();
        assertEquals(2, view.framesReceived());
        assertSame(session.latestFrame().orElseThrow(), view.frame());
        assertTrue(view.controlEnabled());
        assertEquals(1, view.generation());
        assertEquals(ViewerPhase.ACTIVE, view.phase());
        assertEquals(0, view.inputEpoch());
    }

    @Test
    void controlViewNeverEnabledWithoutFrame() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            ViewerSession session = active();
            Future<?> publisher = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
                return null;
            });
            Future<ControlView> reader = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                return session.controlView();
            });
            ControlView view = reader.get(WAIT_S, TimeUnit.SECONDS);
            publisher.get(WAIT_S, TimeUnit.SECONDS);
            assertFalse(view.controlEnabled() && view.frame() == null, "iteration " + i);
            if (view.controlEnabled()) assertEquals(1, view.framesReceived(), "iteration " + i);
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        }
    }

    // ---- disconnect, rtt, traffic ------------------------------------------------------------

    @Test
    void disconnectClosesWithUserAndSendsDisconnect() throws Exception {
        ViewerSession session = active();
        assertEquals(List.of(PacketType.PONG), written(session, 1)); // the writer is running
        session.cancel();
        CloseInfo info = session.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.USER, info.reason());
        assertTrue(info.sendDisconnect());
        session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(session.lifecycle().closeInfo().isPresent());
        assertEquals(List.of(PacketType.PONG, PacketType.DISCONNECT), written(session, 2));
    }

    @Test
    void rttEmptyUntilMatchingPong() throws Exception {
        FakeClock clock = new FakeClock();
        Ticker ticker = new Ticker(Duration.ofMillis(5), clock);
        tickers.add(ticker);
        ViewerSession session = active(clock, ticker, new Sink(null));
        assertTrue(session.rttNanos().isEmpty());
        clock.set(s(2)); // the heartbeat sends PING(1) on the next ticker tick
        List<Packet> packets = writtenPackets(session, 2);
        assertEquals(PacketType.PING, packets.get(1).type());
        assertEquals(1, packets.get(1).seq());
        ViewerTestAccess.dispatch(session, Packet.pong(99)); // does not match the outstanding ping
        assertTrue(session.rttNanos().isEmpty());
        clock.set(s(2) + Duration.ofMillis(40).toNanos());
        ViewerTestAccess.dispatch(session, Packet.pong(1));
        assertEquals(40_000_000L, session.rttNanos().orElseThrow());
    }

    @Test
    void trafficReportsPerChannelBytes() throws Exception {
        HostCore core = hostResources.core(new HostConfig(InetAddress.getLoopbackAddress(), 0, 0, TIMEOUTS,
                System::nanoTime, new SecureRandom()));
        Ticker ticker = new Ticker(Duration.ofMillis(100), System::nanoTime);
        tickers.add(ticker);
        FakeInputDevice device = new FakeInputDevice();
        InputExecutor executor = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 256,
                InputExecutor.defaultThreadFactory(), t -> { });
        ViewerSession client = null;
        try {
            core.start();
            ViewerConfig cfg = new ViewerConfig("127.0.0.1", core.cmdPort(), core.imgPort(),
                    core.sessions().currentCode(), TIMEOUTS, System::nanoTime);
            client = new ViewerSession(cfg, 1, SocketConnector.DEFAULT,
                    ViewerHooks.none(), ticker, scheduler, cleanup);
            assertTrue(client.start());
            client.activated().get(WAIT_S, TimeUnit.SECONDS);
            HostSession server = core.sessions().current().orElseThrow();
            awaitTrue(() -> server.framesSent() > 0);
            ViewerSession viewer = client;
            awaitTrue(() -> viewer.framesReceived() > 0);

            TrafficSnapshot t = server.traffic();
            assertTrue(t.cmdOut() > 0, "SESSION_ACTIVE went out on the command channel");
            assertTrue(t.cmdIn() > 0, "AUTH came in on the command channel");
            assertTrue(t.imgOut() > 0, "frames went out on the image channel");
            assertEquals(26, t.imgIn(), "the Host read only the framed ATTACH on the image channel");

            TrafficSnapshot c = client.traffic();
            assertTrue(c.cmdOut() > 0);
            assertTrue(c.cmdIn() > 0);
            assertTrue(c.imgIn() > 0);
            assertEquals(26, c.imgOut(), "the Viewer wrote only the framed ATTACH on the image channel");
            assertTrue(client.framesReceived() > 0);
        } finally {
            if (client != null) client.cancel();
            core.close();
            executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached in time");
            LockSupport.parkNanos(1_000_000);
        }
    }

    private final FakeClock clock = new FakeClock();
    private ViewerSession reserved(Sink sink) throws Exception { return reserved(clock, null, sink); }
    private ViewerSession active(Sink sink) throws Exception { return active(clock, null, sink); }
    private Sink gatedSink() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return new Sink(gate);
    }
    private static void awaitEntered(Sink sink) throws Exception {
        assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS), "the writer is stuck inside its first write");
    }
    private static CloseInfo closeInfo(ViewerSession session) { return session.lifecycle().closeInfo().orElseThrow(); }
    /** With the writer stuck in its first write, fills the 256-slot queue with input the gate lets through. */
    private static void fillWithInput(ViewerSession session) {
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) {
            assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(i + 1)), "fill " + i);
        }
    }

    @Test
    void epochAndActiveInstantAreEmptyUntilActive() throws Exception {
        ViewerSession session = reserved(new Sink(null));
        assertEquals(0, session.inputEpoch());
        assertTrue(session.activeAtNanos().isEmpty());

        clock.set(s(2));
        ViewerTestAccess.dispatch(session, Packet.screenInfo(1920, 1080));
        Channel img = new Channel(new Socket(), new DataInputStream(new IdleInput()),
                new DataOutputStream(OutputStream.nullOutputStream()));
        session.lifecycle().register(img.socket());
        session.lifecycle().addStopHook(img::closeWrappersQuietly);
        ViewerTestAccess.attachImageChannel(session, img);
        ViewerTestAccess.dispatch(session, Packet.sessionActive());

        assertEquals(ViewerPhase.ACTIVE, session.phase());
        assertEquals(s(2), session.activeAtNanos().orElseThrow());
        assertEquals(0, session.inputEpoch(), "ACTIVE with no stall yet is still revision 0");
    }

    // ---- frames ---------------------------------------------------------------------------------

    @Test
    void frameOfAnotherGenerationIsDropped() throws Exception {
        ViewerSession session = active(new Sink(null));

        ViewerTestAccess.controlSide(session).publishFrame(2, image(), s(1)); // the session is generation 1

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
        assertFalse(session.controlEnabled(), "a dropped frame cannot open the gate");
    }

    @Test
    void frameBeforeActiveIsDropped() throws Exception {
        ViewerSession session = reserved(new Sink(null));

        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
        assertEquals(ViewerPhase.ATTACHING, session.phase());
    }

    @Test
    void frameAfterCloseIsDropped() throws Exception {
        ViewerSession session = active(new Sink(null));
        session.cancel();

        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
    }

    @Test
    void tickOnAClosedActiveSessionDecidesNothingMore() throws Exception {
        ViewerSession session = active(new Sink(null));
        session.cancel();
        CloseInfo first = closeInfo(session);

        session.tick(s(100)); // far past the frame timeout, but the session is already closing

        assertSame(first, closeInfo(session), "the first close reason stays");
        assertEquals(Packet.DisconnectReason.USER, first.reason());
        assertEquals(0, session.releaseAllSent());
        assertEquals(0, session.inputEpoch(), "no stall or timeout transition was recorded");
    }

    // ---- overflow on the paths that queue input -------------------------------------------------

    @Test
    void stallReleaseThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ViewerSession session = active(sink);
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        session.tick(s(4)); // 3 s without a frame: the stall decision must queue RELEASE_ALL

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals(0, session.releaseAllSent(), "the RELEASE_ALL was not accepted");
    }

    @Test
    void inputThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ViewerSession session = active(sink);
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        assertEquals(CommandWriter.OfferResult.OVERFLOW, session.offerInput(Packet.keyPress(999)));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals("command queue overflow", info.message());
    }

    @Test
    void epochCheckedInputThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ViewerSession session = active(sink);
        ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        assertEquals(InputOutcome.OVERFLOW, session.offerInput(0, Packet.keyPress(999)));

        assertEquals(Packet.DisconnectReason.OVERFLOW, closeInfo(session).reason());
    }

    @Test
    void releaseControlInputOnAClosedSessionReturnsClosed() throws Exception {
        ViewerSession session = active(new Sink(null));
        session.cancel();

        assertEquals(CommandWriter.OfferResult.CLOSED, session.releaseControlInput());

        assertEquals(0, session.controlReleasesSent());
    }


    @RepeatedTest(20)
    void stallRacingInputNeverQueuesKeyAfterReleaseAll() throws Exception {
        for (boolean stallFirst : new boolean[] {true, false}) {
            ViewerSession session = active();
            ViewerTestAccess.controlSide(session).publishFrame(1, image(), s(1));
            CyclicBarrier start = new CyclicBarrier(2);
            CountDownLatch first = new CountDownLatch(1);
            Future<?> stall = pool.submit(() -> {
                start.await(WAIT_S, TimeUnit.SECONDS);
                if (!stallFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                session.tick(s(4));
                if (stallFirst) first.countDown();
                return null;
            });
            Future<InputOutcome> input = pool.submit(() -> {
                start.await(WAIT_S, TimeUnit.SECONDS);
                if (stallFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                InputOutcome outcome = session.offerInput(0, Packet.keyPress(17));
                if (!stallFirst) first.countDown();
                return outcome;
            });
            try {
                stall.get(WAIT_S, TimeUnit.SECONDS);
                assertEquals(stallFirst ? InputOutcome.GATE_CLOSED : InputOutcome.SENT, input.get(WAIT_S, TimeUnit.SECONDS));
                assertEquals(1, session.releaseAllSent());
                assertFalse(session.controlEnabled());
                assertEquals(stallFirst ? List.of(PacketType.PONG, PacketType.RELEASE_ALL)
                        : List.of(PacketType.PONG, PacketType.KEY_PRESS, PacketType.RELEASE_ALL), written(session, stallFirst ? 2 : 3));
            } finally { first.countDown(); session.cancel(); }
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        }
    }

    @Test void controlViewUsesConnectorPhaseAndConsistentGeneration() throws Exception {
        ViewerSession session = new ViewerSession(new ViewerConfig("127.0.0.1", 1, 2, "123456", TIMEOUTS, () -> 0L),
                91, SocketConnector.DEFAULT, ViewerHooks.none(), idleTicker, scheduler, cleanup);
        sessions.add(session);
        ViewerPhase initial = session.controlView().phase();
        assertEquals(ViewerPhase.CONNECTING, initial);
        assertEquals(91, session.controlView().generation());
        session.lifecycle().atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING));
        assertEquals(ViewerPhase.AUTHENTICATING, session.controlView().phase());
        Channel command = new Channel(new Socket(), new DataInputStream(new IdleInput()),
                new DataOutputStream(new ByteArrayOutputStream()));
        session.lifecycle().register(command.socket());
        session.lifecycle().addStopHook(command::closeWrappersQuietly);
        assertTrue(ViewerTestAccess.attachCommandChannel(session, command));
        assertEquals(ViewerPhase.ATTACHING, session.controlView().phase());
        ViewerTestAccess.dispatch(session, Packet.screenInfo(1920, 1080));
        Channel imageChannel = new Channel(new Socket(), new DataInputStream(new IdleInput()),
                new DataOutputStream(OutputStream.nullOutputStream()));
        session.lifecycle().register(imageChannel.socket());
        session.lifecycle().addStopHook(imageChannel::closeWrappersQuietly);
        ViewerTestAccess.attachImageChannel(session, imageChannel);
        ViewerTestAccess.dispatch(session, Packet.sessionActive());
        assertEquals(ViewerPhase.ACTIVE, session.controlView().phase());
        assertFalse(session.controlView().controlEnabled());
        BufferedImage frame = image();
        ViewerTestAccess.controlSide(session).publishFrame(91, frame, s(1));
        ControlView view = session.controlView();
        assertEquals(ViewerPhase.ACTIVE, view.phase());
        assertEquals(91, view.generation());
        assertEquals(0, view.inputEpoch());
        assertSame(frame, view.frame());
        assertEquals(1, view.framesReceived());
        assertTrue(view.controlEnabled());
        assertFalse(view.controlEnabled() && view.frame() == null);
        session.cancel();
        assertEquals(ViewerPhase.CLOSING, session.controlView().phase());
        assertFalse(session.controlView().controlEnabled());
        session.terminated().get(WAIT_S, TimeUnit.SECONDS);
    }
}
