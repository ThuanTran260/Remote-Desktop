package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.SocketConnector;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The control view, the epoch-checked input and the release/traffic/rtt accessors of the Server
 * session. Same harness as {@link ServerSessionGateTest}: a real writer thread writes to a
 * capturing sink, so the order of packets on the wire is observable; every ordering is forced
 * with latches and the fake clock.
 */
@Timeout(120)
class ServerSessionControlTest {
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
    private final List<ServerSession> sessions = new ArrayList<>();
    private final List<Sink> sinks = new ArrayList<>();
    private final List<Ticker> tickers = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void shutdown() {
        gates.forEach(CountDownLatch::countDown);
        for (ServerSession s : sessions) s.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        tickers.forEach(Ticker::close);
        pool.shutdownNow();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
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

    private ServerSession newSession(LongSupplier clock, Ticker ticker) {
        SessionLifecycle<ServerPhase> lifecycle = new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        ServerSession.Env env = new ServerSession.Env(TIMEOUTS, clock, ticker, () -> false, null, false);
        ServerSession session = new ServerSession(1, TOKEN, InetAddress.getLoopbackAddress(), 0, lifecycle, env);
        sessions.add(session);
        return session;
    }

    /** A RESERVED session whose command channel is attached (no SCREEN_INFO, no image channel). */
    private ServerSession reserved(LongSupplier clock, Ticker ticker, Sink sink) throws Exception {
        ServerSession session = newSession(clock, ticker);
        sinks.add(sink);
        PendingConnection cmdPending = new PendingConnection(new Socket(), 0);
        Channel cmd = new Channel(cmdPending.socket(), new DataInputStream(new IdleInput()), new DataOutputStream(sink));
        assertTrue(session.attachCommandChannel(cmdPending, cmd, 0));
        return session;
    }

    private ServerSession active() throws Exception {
        return active(() -> 0L, null, new Sink(null));
    }

    private ServerSession active(LongSupplier clock, Ticker ticker, Sink sink) throws Exception {
        ServerSession session = reserved(clock, ticker, sink);
        session.dispatch(Packet.screenInfo(1920, 1080));
        PendingConnection imgPending = new PendingConnection(new Socket(), 0);
        Channel img = new Channel(imgPending.socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        assertEquals(ServerSession.AttachResult.ATTACHED,
                session.attachImage(Packet.attach(TOKEN).attachPayload(), imgPending, img, 0));
        assertEquals(ServerPhase.ACTIVE, session.phase());
        return session;
    }

    private static BufferedImage image() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    private List<Packet> writtenPackets(ServerSession session, int count) throws Exception {
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

    private List<PacketType> written(ServerSession session, int count) throws Exception {
        return writtenPackets(session, count).stream().map(Packet::type).toList();
    }

    // ---- epoch ---------------------------------------------------------------------------------

    @Test
    void inputEpochStartsAtZeroAndTracksMonitorRevision() throws Exception {
        ServerSession session = active();
        List<Long> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        session.addFrameObserver((revision, t) -> seen.add(revision));
        assertEquals(0, session.inputEpoch(), "before the first frame");
        session.tick(s(3)); // STALL_START
        assertEquals(1, session.inputEpoch());
        session.publishFrame(1, image(), s(4)); // STALL_END
        assertEquals(2, session.inputEpoch());
        assertEquals(List.of(1L, 2L), seen, "the epoch equals the revision the observer saw");
    }

    @Test
    void offerInputWithCurrentEpochIsSent() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(17)));
        List<Packet> packets = writtenPackets(session, 2);
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.KEY_PRESS),
                packets.stream().map(Packet::type).toList());
        assertEquals(17, packets.get(1).keyCode());
    }

    @Test
    void offerInputDuringStallIsGateClosed() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        session.tick(s(4)); // STALL_START: epoch 1
        assertEquals(1, session.inputEpoch());
        assertEquals(InputOutcome.GATE_CLOSED, session.offerInput(1, Packet.keyPress(17)));
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.RELEASE_ALL), written(session, 2));
    }

    @Test
    void offerInputWithOldEpochAfterRecoveryIsStale() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        long captured = session.inputEpoch();
        assertEquals(0, captured);
        session.tick(s(4)); // STALL_START
        session.publishFrame(1, image(), s(5)); // STALL_END
        assertEquals(2, session.inputEpoch());
        assertEquals(InputOutcome.STALE_EPOCH, session.offerInput(captured, Packet.keyPress(17)));
        assertEquals(InputOutcome.SENT, session.offerInput(2, Packet.keyPress(17)));
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.RELEASE_ALL, PacketType.KEY_PRESS),
                written(session, 3));
    }

    @Test
    void epochCheckGateAndOfferAreOneTransaction() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1)); // gate open at epoch 0
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
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.RELEASE_ALL), written(session, 2));
    }

    @Test
    void offerInputRejectsNonInputPackets() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(0, Packet.ping(1)));
        assertThrows(IllegalArgumentException.class, () -> session.offerInput(0, Packet.releaseAll()));
    }

    // ---- release ---------------------------------------------------------------------------------

    @Test
    void releaseControlInputWorksWhileGateClosed() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        session.tick(s(4)); // STALL_START: the gate is closed and RELEASE_ALL (stall) queued
        assertFalse(session.controlEnabled());
        assertEquals(CommandWriter.OfferResult.ACCEPTED, session.releaseControlInput());
        assertEquals(1, session.releaseAllSent());
        assertEquals(1, session.controlReleasesSent());
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.RELEASE_ALL, PacketType.RELEASE_ALL),
                written(session, 3));
    }

    @Test
    void releaseControlInputBeforeActiveReturnsClosed() throws Exception {
        Sink sink = new Sink(null);
        ServerSession session = reserved(() -> 0L, null, sink);
        assertEquals(ServerPhase.RESERVED, session.phase());
        assertEquals(CommandWriter.OfferResult.CLOSED, session.releaseControlInput());
        assertEquals(0, session.controlReleasesSent());
        assertEquals(0, sink.snapshot().length, "nothing was queued");
    }

    @Test
    void releaseControlInputOverflowClosesSessionWithOverflow() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        Sink sink = new Sink(gate);
        ServerSession session = active(() -> 0L, null, sink);
        session.publishFrame(1, image(), s(1));
        assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS), "the writer is stuck inside its first write");
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) {
            assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(i + 1)), "fill " + i);
        }
        assertEquals(CommandWriter.OfferResult.OVERFLOW, session.releaseControlInput());
        assertEquals(Packet.DisconnectReason.OVERFLOW, session.closeInfo().orElseThrow().reason());
        assertEquals(0, session.controlReleasesSent());
    }

    // ---- control view ----------------------------------------------------------------------------

    @Test
    void controlViewIsOneConsistentSnapshot() throws Exception {
        ServerSession session = active();
        session.publishFrame(1, image(), s(1));
        session.publishFrame(1, image(), s(1));
        ControlView view = session.controlView();
        assertEquals(2, view.framesReceived());
        assertSame(session.latestFrame().orElseThrow(), view.frame());
        assertTrue(view.controlEnabled());
        assertEquals(1, view.generation());
        assertEquals(ServerPhase.ACTIVE, view.phase());
        assertEquals(0, view.inputEpoch());
    }

    @Test
    void controlViewNeverEnabledWithoutFrame() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            ServerSession session = active();
            Future<?> publisher = pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                session.publishFrame(1, image(), s(1));
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
        ServerSession session = active();
        assertEquals(List.of(PacketType.SESSION_ACTIVE), written(session, 1)); // the writer is running
        session.disconnect();
        CloseInfo info = session.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.USER, info.reason());
        assertTrue(info.sendDisconnect());
        session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(session.closeInfo().isPresent());
        assertEquals(List.of(PacketType.SESSION_ACTIVE, PacketType.DISCONNECT), written(session, 2));
    }

    @Test
    void rttEmptyUntilMatchingPong() throws Exception {
        FakeClock clock = new FakeClock();
        Ticker ticker = new Ticker(Duration.ofMillis(5), clock);
        tickers.add(ticker);
        ServerSession session = active(clock, ticker, new Sink(null));
        assertTrue(session.rttNanos().isEmpty());
        clock.set(s(2)); // the heartbeat sends PING(1) on the next ticker tick
        List<Packet> packets = writtenPackets(session, 2);
        assertEquals(PacketType.PING, packets.get(1).type());
        assertEquals(1, packets.get(1).seq());
        session.dispatch(Packet.pong(99)); // does not match the outstanding ping
        assertTrue(session.rttNanos().isEmpty());
        clock.set(s(2) + Duration.ofMillis(40).toNanos());
        session.dispatch(Packet.pong(1));
        assertEquals(40_000_000L, session.rttNanos().orElseThrow());
    }

    @Test
    void trafficReportsPerChannelBytes() throws Exception {
        ServerCore core = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, TIMEOUTS,
                System::nanoTime, new SecureRandom()));
        Ticker ticker = new Ticker(Duration.ofMillis(100), System::nanoTime);
        tickers.add(ticker);
        FakeInputDevice device = new FakeInputDevice();
        InputExecutor executor = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 256,
                InputExecutor.defaultThreadFactory(), t -> { });
        ClientSession client = null;
        try {
            core.start();
            ClientConfig cfg = new ClientConfig("127.0.0.1", core.cmdPort(), core.imgPort(),
                    core.sessions().currentCode(), TIMEOUTS, System::nanoTime);
            client = new ClientSession(cfg, 1, executor, new FakeScreenSource(640, 360), SocketConnector.DEFAULT,
                    ClientHooks.none(), ticker, scheduler, cleanup);
            assertTrue(client.start());
            client.activated().get(WAIT_S, TimeUnit.SECONDS);
            ServerSession server = core.sessions().current().orElseThrow();
            awaitTrue(() -> server.framesReceived() > 0);

            TrafficSnapshot t = server.traffic();
            assertTrue(t.cmdOut() > 0, "SESSION_ACTIVE went out on the command channel");
            assertTrue(t.cmdIn() > 0, "SCREEN_INFO came in on the command channel");
            assertTrue(t.imgIn() > 0, "frames came in on the image channel");
            assertEquals(0, t.imgOut(), "nothing is ever written to the image channel");

            TrafficSnapshot c = client.traffic();
            assertTrue(c.cmdOut() > 0);
            assertTrue(c.cmdIn() > 0);
            assertTrue(c.imgOut() > 0);
            assertEquals(0, c.imgIn());
            assertTrue(client.framesSent() > 0);
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
}
