package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientPhase;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerPhase;
import com.ltm.rd.server.ServerSession;
import com.ltm.rd.server.ServerTestAccess;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Real ClientSession against the real ServerCore, or against a raw PacketIO server where a test
 * must control exactly when the server speaks. Timeout tests assert the exact close cause and the
 * decision window [deadline, deadline + 600 ms]; terminated() is awaited separately.
 */
@Timeout(120)
class ClientServerIntegrationTest {
    private static final int WAIT_S = 10;
    private static final long SLACK_MS = 600;
    private static final Duration GRACE = Duration.ofMillis(300);

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
    private final Ticker ticker = new Ticker(Duration.ofMillis(100), System::nanoTime);
    private final FakeInputDevice device = new FakeInputDevice();
    private final InputExecutor executor = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 256,
            InputExecutor.defaultThreadFactory(), t -> {});
    private final AtomicLong generations = new AtomicLong();
    private final List<ClientSession> clients = new ArrayList<>();
    private final List<ServerCore> cores = new ArrayList<>();
    private final List<RawPeer.Server> rawServers = new ArrayList<>();
    private final List<RawPeer> peers = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        for (ClientSession c : clients) c.cancel();
        for (RawPeer p : peers) p.close();
        for (RawPeer.Server s : rawServers) s.close();
        for (ServerCore c : cores) c.close();
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        ticker.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    // ---- fixtures --------------------------------------------------------------------------

    private static Timeouts timeouts(Duration reserve, Duration clientAuth, Duration clientActivation,
            Duration liveness) {
        return new Timeouts(Duration.ofSeconds(1), reserve, clientAuth, clientActivation, Duration.ofMillis(200),
                liveness, Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15),
                Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(10), GRACE, Duration.ofSeconds(3),
                Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));
    }

    /** Generous handshake and auth windows for tests that do not measure a deadline. */
    private static Timeouts relaxed() {
        return new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(5),
                Duration.ofSeconds(10), GRACE, Duration.ofSeconds(3), Duration.ZERO, Duration.ofSeconds(30),
                Duration.ofMillis(100));
    }

    private static Timeouts normal() {
        return timeouts(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    private ServerCore core(Timeouts t) throws IOException {
        ServerCore c = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, t, System::nanoTime,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    private RawPeer.Server rawServer() throws IOException {
        RawPeer.Server s = new RawPeer.Server();
        rawServers.add(s);
        return s;
    }

    private RawPeer track(RawPeer p) {
        peers.add(p);
        return p;
    }

    private ClientSession client(int cmdPort, int imgPort, String code, Timeouts t, SocketConnector connector,
            ClientHooks hooks) {
        return client(cmdPort, imgPort, code, t, connector, hooks, ticker);
    }

    private ClientSession client(int cmdPort, int imgPort, String code, Timeouts t, SocketConnector connector,
            ClientHooks hooks, Ticker withTicker) {
        ClientConfig cfg = new ClientConfig("127.0.0.1", cmdPort, imgPort, code, t, System::nanoTime);
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), executor,
                new FakeScreenSource(1920, 1080), connector, hooks, withTicker, scheduler, cleanup);
        clients.add(c);
        return c;
    }

    private ClientSession client(ServerCore core, String code, Timeouts t, ClientHooks hooks) {
        return client(core.cmdPort(), core.imgPort(), code, t, SocketConnector.DEFAULT, hooks);
    }

    private ClientSession client(ServerCore core, Timeouts t) {
        return client(core, core.sessions().currentCode(), t, ClientHooks.none());
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static void assertWindow(long actualMs, long loMs, long hiMs, String what) {
        assertTrue(actualMs >= loMs && actualMs <= hiMs, what + ": " + actualMs + " ms not in [" + loMs + ", "
                + hiMs + "]");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS), "timed out waiting for " + what);
    }

    /** A hook that signals it was reached and then waits (interruptibly) for a release. */
    private final class HeldHook implements Runnable {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        HeldHook() {
            releases.add(release);
        }

        @Override
        public void run() {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // the close procedure interrupts workers: carry on so the thread can end
            }
        }
    }

    private void awaitTerminated(ClientSession c) throws Exception {
        c.terminated().get(5, TimeUnit.SECONDS);
        assertEquals(0, c.lifecycle().liveWorkers());
    }

    /** Records when a close decision was taken and why, from a close hook. */
    private static final class CloseRecorder {
        final CountDownLatch decided = new CountDownLatch(1);
        volatile long decidedAtNanos;
        volatile CloseInfo info;

        CloseRecorder(ClientSession c) {
            c.lifecycle().addCloseHook(() -> {
                decidedAtNanos = System.nanoTime();
                info = c.lifecycle().closeInfo().orElse(null);
                decided.countDown();
            });
        }

        void await() throws InterruptedException {
            assertTrue(decided.await(WAIT_S, TimeUnit.SECONDS), "client session never closed");
        }
    }

    private static void awaitClosed(Socket s) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!s.isClosed() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
    }

    /** Drives a raw server through AUTH and returns the command peer; the client is then ATTACHING. */
    private RawPeer rawAuthOk(RawPeer.Server rs, byte[] token) throws IOException {
        RawPeer cmd = track(rs.acceptCmd());
        Packet auth = cmd.read();
        assertEquals(PacketType.AUTH, auth.type());
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token));
        return cmd;
    }

    private static byte[] token() {
        byte[] t = new byte[16];
        for (int i = 0; i < t.length; i++) t[i] = (byte) (i + 1);
        return t;
    }

    // ---- activation ------------------------------------------------------------------------

    @Test
    void clientReachesActiveAndServerSeesScreenInfo() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = client(core, normal());
        assertTrue(c.start());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, c.phase());
        ServerSession s = core.sessions().current().orElseThrow();
        assertEquals(ServerPhase.ACTIVE, s.phase());
        assertEquals(new Packet.SizePayload(1920, 1080), s.screen().orElseThrow());
        assertEquals(1, c.inputBeginCount());
        assertEquals(InputExecutor.State.OPEN, executor.state());
    }

    @Test
    void sessionActiveBeforeImageHandoverStartsOnce() throws Exception {
        ServerCore core = core(normal());
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(core, core.sessions().currentCode(), normal(),
                new ClientHooks(afterAttach, null));
        c.start();
        await(afterAttach.reached, "ATTACH to be flushed");
        awaitTrue(c::activationReceived, "SESSION_ACTIVE to arrive");
        assertEquals(ClientPhase.ATTACHING, c.phase(), "the image channel is not handed over yet");
        assertEquals(0, c.inputBeginCount());
        assertEquals(InputExecutor.State.IDLE, executor.state());

        afterAttach.release.countDown();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(1, c.inputBeginCount());
        awaitTrue(() -> c.senderRuns() == 1, "the sender to start");
        c.cancel();
        awaitTerminated(c);
        assertEquals(1, c.inputBeginCount(), "input began exactly once");
        assertEquals(1, c.senderRuns());
    }

    @Test
    void imageHandoverBeforeSessionActiveStartsOnce() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        c.start();
        byte[] token = token();
        RawPeer cmd = rawAuthOk(rs, token);
        assertEquals(PacketType.SCREEN_INFO, cmd.readUntil(PacketType.SCREEN_INFO).type());
        RawPeer img = track(rs.acceptImg());
        Packet attach = img.read();
        assertEquals(PacketType.ATTACH, attach.type());
        assertArrayEquals(token, attach.attachPayload().token());

        awaitTrue(c::imageChannelReady, "the image hand-over");
        assertEquals(ClientPhase.ATTACHING, c.phase(), "SESSION_ACTIVE has not been sent yet");
        assertEquals(0, c.inputBeginCount());
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, c.phase());
        assertEquals(1, c.inputBeginCount());
    }

    @Test
    void cancelBetweenActivationAndHandoverStartsNothing() throws Exception {
        ServerCore core = core(normal());
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(core, core.sessions().currentCode(), normal(), new ClientHooks(afterAttach, null));
        c.start();
        await(afterAttach.reached, "ATTACH to be flushed");
        awaitTrue(c::activationReceived, "SESSION_ACTIVE to arrive");
        c.cancel();
        afterAttach.release.countDown();
        awaitTerminated(c);
        assertEquals(0, c.inputBeginCount());
        assertEquals(InputExecutor.State.IDLE, executor.state());
        assertTrue(c.activated().isCompletedExceptionally(), "never became ACTIVE");
        assertEquals(0, c.senderRuns());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    enum HandshakePoint { DURING_CONNECT, AFTER_AUTH_SENT, AFTER_AUTH_RESULT, AFTER_ATTACH_FLUSHED }

    @ParameterizedTest
    @EnumSource(HandshakePoint.class)
    void cancelAtEachHandshakePoint(HandshakePoint point) throws Exception {
        ClientSession c;
        HeldHook afterAttach = new HeldHook();
        switch (point) {
            case DURING_CONNECT -> {
                CountDownLatch entered = new CountDownLatch(1);
                SocketConnector blocking = (s, addr, timeoutMs) -> {
                    entered.countDown();
                    awaitClosed(s);
                    throw new IOException("closed while connecting");
                };
                c = client(1, 1, "123456", normal(), blocking, ClientHooks.none());
                c.start();
                await(entered, "connect to start");
            }
            case AFTER_AUTH_SENT -> {
                RawPeer.Server rs = rawServer();
                c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                        ClientHooks.none());
                c.start();
                RawPeer cmd = track(rs.acceptCmd());
                assertEquals(PacketType.AUTH, cmd.read().type());
            }
            case AFTER_AUTH_RESULT -> {
                RawPeer.Server rs = rawServer();
                c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                        ClientHooks.none());
                c.start();
                rawAuthOk(rs, token());
                ClientSession started = c;
                awaitTrue(() -> started.phase() == ClientPhase.ATTACHING, "ATTACHING");
            }
            case AFTER_ATTACH_FLUSHED -> {
                RawPeer.Server rs = rawServer();
                c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                        new ClientHooks(afterAttach, null));
                c.start();
                rawAuthOk(rs, token());
                assertEquals(PacketType.ATTACH, track(rs.acceptImg()).read().type());
                await(afterAttach.reached, "ATTACH to be flushed");
            }
            default -> throw new AssertionError(point);
        }
        c.cancel();
        afterAttach.release.countDown();
        awaitTerminated(c);
        assertEquals(0, c.inputBeginCount(), "input must never begin after an early cancel");
        assertEquals(InputExecutor.State.IDLE, executor.state());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void cancelAfterBeginInputBeforeSenderRuns() throws Exception {
        ServerCore core = core(normal());
        HeldHook beforeSender = new HeldHook();
        ClientSession c = client(core, core.sessions().currentCode(), normal(), new ClientHooks(null, beforeSender));
        c.start();
        await(beforeSender.reached, "the sender start point");
        assertEquals(ClientPhase.ACTIVE, c.phase());
        assertEquals(1, c.inputBeginCount());
        c.cancel();
        beforeSender.release.countDown();
        awaitTerminated(c);
        assertEquals(0, c.senderRuns(), "the sender body must never run after cancel");
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    // ---- failures --------------------------------------------------------------------------

    @Test
    void wrongCodeReportedToClient() throws Exception {
        ServerCore core = core(relaxed());
        String code = core.sessions().currentCode();
        ClientSession c = client(core, code.equals("000000") ? "000001" : "000000", relaxed(), ClientHooks.none());
        c.start();
        awaitTerminated(c);
        assertEquals(Packet.AuthStatus.WRONG_CODE, c.authFailure().orElseThrow());
        CloseInfo info = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("auth rejected: WRONG_CODE", info.message());
        assertEquals(0, c.inputBeginCount());
        assertTrue(core.sessions().current().isEmpty());
    }

    @Test
    void serverNotRunningClosesCleanly() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freePort = probe.getLocalPort();
        }
        ClientSession c = client(freePort, freePort, "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        c.start();
        awaitTerminated(c);
        CloseInfo info = c.lifecycle().closeInfo().orElseThrow();
        assertEquals("connect failed", info.message());
        assertEquals(0, c.inputBeginCount());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.CLOSING, c.phase());
    }

    @Test
    void cancelDuringBlockedConnectReleasesThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        SocketConnector blocking = (s, addr, timeoutMs) -> {
            entered.countDown();
            awaitClosed(s);
            throw new IOException("closed while connecting");
        };
        ClientSession c = client(1, 1, "123456", normal(), blocking, ClientHooks.none());
        c.start();
        await(entered, "connect to start");
        long start = System.nanoTime();
        c.cancel();
        c.terminated().get(5, TimeUnit.SECONDS);
        assertTrue(ms(System.nanoTime() - start) < 500, "the blocked connect thread must be released at once");
        assertEquals(0, c.lifecycle().liveWorkers());
    }

    @Test
    void noAuthResultClosesAfterClientAuthDeadline() throws Exception {
        RawPeer.Server rs = rawServer();
        Timeouts t = timeouts(Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(3),
                Duration.ofSeconds(3));
        AtomicLong connectedAt = new AtomicLong();
        SocketConnector recording = (s, addr, timeoutMs) -> {
            SocketConnector.DEFAULT.connect(s, addr, timeoutMs);
            connectedAt.compareAndSet(0, System.nanoTime());
        };
        ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", t, recording, ClientHooks.none());
        CloseRecorder rec = new CloseRecorder(c);
        c.start();
        assertEquals(PacketType.AUTH, track(rs.acceptCmd()).read().type()); // the server then stays silent
        rec.await();
        assertEquals("auth timeout", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - connectedAt.get()), 500, 500 + SLACK_MS, "auth timeout decision");
        c.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(ms(System.nanoTime() - rec.decidedAtNanos) <= GRACE.toMillis() + SLACK_MS);
        awaitTerminated(c);
    }

    @Test
    void noSessionActiveClosesAfterActivationDeadline() throws Exception {
        RawPeer.Server rs = rawServer();
        Timeouts t = timeouts(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(600),
                Duration.ofSeconds(5));
        ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", t, SocketConnector.DEFAULT,
                ClientHooks.none());
        CloseRecorder rec = new CloseRecorder(c);
        c.start();
        RawPeer cmd = track(rs.acceptCmd());
        assertEquals(PacketType.AUTH, cmd.read().type());
        long okSentAt = System.nanoTime();
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));
        track(rs.acceptImg()); // the image channel attaches, but SESSION_ACTIVE never comes
        rec.await();
        assertEquals("activation timeout", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - okSentAt), 600, 600 + SLACK_MS, "activation timeout decision");
        c.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        awaitTerminated(c);
        assertEquals(0, c.inputBeginCount());
    }

    @Test
    void serverEofWhileActiveClosesClientImmediately() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = client(core, normal());
        c.start();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        CloseRecorder rec = new CloseRecorder(c);
        ServerSession server = core.sessions().current().orElseThrow();
        long start = System.nanoTime();
        ServerTestAccess.lifecycle(server).close(new CloseInfo(Packet.DisconnectReason.USER, "test", false)); // closes, no DISCONNECT
        rec.await();
        assertTrue(ms(rec.decidedAtNanos - start) < 500, "EOF must close the client at once, not after liveness");
        // EOF or, if a PING was unread when the server closed, a reset: either way an EOF cause, never a timeout
        assertNotEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertTrue(java.util.Set.of("peer closed command channel", "command channel lost").contains(rec.info.message()),
                rec.info.message());
        awaitTerminated(c);
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void reconnectWithOldCodeFailsNewCodeWorks() throws Exception {
        ServerCore core = core(normal());
        String oldCode = core.sessions().currentCode();
        ClientSession first = client(core, oldCode, normal(), ClientHooks.none());
        first.start();
        first.activated().get(WAIT_S, TimeUnit.SECONDS);
        first.cancel();
        awaitTerminated(first);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the server to free the slot");
        String newCode = core.sessions().currentCode();
        assertNotEquals(oldCode, newCode);

        ClientSession stale = client(core, oldCode, normal(), ClientHooks.none());
        stale.start();
        awaitTerminated(stale);
        assertEquals(Packet.AuthStatus.WRONG_CODE, stale.authFailure().orElseThrow());

        ClientSession fresh = client(core, newCode, normal(), ClientHooks.none());
        fresh.start();
        fresh.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, fresh.phase());
    }

    @Test
    void heldKeyReleasedWhenConnectionDrops() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        c.start();
        RawPeer cmd = rawAuthOk(rs, token());
        cmd.readUntil(PacketType.SCREEN_INFO);
        RawPeer img = track(rs.acceptImg());
        assertEquals(PacketType.ATTACH, img.read().type());
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);

        cmd.send(Packet.keyPress(17));
        assertTrue(device.awaitCall("keyPress:17"));
        cmd.close(); // the connection drops while the key is held
        img.close();
        assertTrue(device.awaitCall("keyRelease:17"), "the held key must be released");
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        awaitTerminated(c);
    }

    // ---- fallbacks and isolation -----------------------------------------------------------

    @Test
    void authReadFallbackEndsTheReadWhenTheTickerIsStalled() throws Exception {
        RawPeer.Server rs = rawServer();
        Ticker stalled = new Ticker(Duration.ofHours(1), System::nanoTime); // never ticks within this test
        try {
            Timeouts t = timeouts(Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(3),
                    Duration.ofSeconds(3));
            AtomicLong connectedAt = new AtomicLong();
            SocketConnector recording = (s, addr, timeoutMs) -> {
                SocketConnector.DEFAULT.connect(s, addr, timeoutMs);
                connectedAt.compareAndSet(0, System.nanoTime());
            };
            ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", t, recording, ClientHooks.none(), stalled);
            CloseRecorder rec = new CloseRecorder(c);
            c.start();
            assertEquals(PacketType.AUTH, track(rs.acceptCmd()).read().type()); // the server stays silent
            rec.await();
            assertEquals("auth timeout", rec.info.message());
            assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
            assertWindow(ms(rec.decidedAtNanos - connectedAt.get()), 500, 500 + SLACK_MS, "soTimeout fallback");
            awaitTerminated(c);
        } finally {
            stalled.close();
        }
    }

    @Test
    void oldSessionLateCallbacksDoNotTouchTheNewSession() throws Exception {
        RawPeer.Server rs1 = rawServer();
        ClientSession a = client(rs1.cmdPort(), rs1.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        a.start();
        rawActive(rs1, a);
        long oldGeneration = generations.get();
        a.cancel();
        awaitTerminated(a);
        a.inputReleased().get(WAIT_S, TimeUnit.SECONDS);

        RawPeer.Server rs2 = rawServer();
        ClientSession b = client(rs2.cmdPort(), rs2.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        b.start();
        RawPeer cmdB = rawActive(rs2, b);
        assertEquals(ClientPhase.ACTIVE, b.phase());

        // everything the old session might still do late: close again, tick far in the future, end its generation
        a.cancel();
        a.tick(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
        executor.endSession(oldGeneration).get(WAIT_S, TimeUnit.SECONDS);

        assertEquals(ClientPhase.ACTIVE, b.phase());
        assertEquals(InputExecutor.State.OPEN, executor.state());
        assertEquals(1, b.inputBeginCount());
        cmdB.send(Packet.keyPress(17));
        assertTrue(device.awaitCall("keyPress:17"), "the new session still drives input");
    }

    /** Drives a raw server through the handshake until the client is ACTIVE; returns the command peer. */
    private RawPeer rawActive(RawPeer.Server rs, ClientSession c) throws Exception {
        RawPeer cmd = rawAuthOk(rs, token());
        cmd.readUntil(PacketType.SCREEN_INFO);
        RawPeer img = track(rs.acceptImg());
        assertEquals(PacketType.ATTACH, img.read().type());
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        return cmd;
    }
}
