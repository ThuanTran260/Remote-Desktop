package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientPhase;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputExecutor;
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
import org.junit.jupiter.api.RepeatedTest;
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


    private final com.ltm.rd.server.ServerTestAccess.HostResources hostResources = new com.ltm.rd.server.ServerTestAccess.HostResources(executor);

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
        hostResources.close();
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
        ServerCore c = hostResources.core(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, t, System::nanoTime,
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
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), connector, hooks, withTicker, scheduler, cleanup);
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
        assertEquals(1, core.sessions().current().orElseThrow().inputBeginCount());
        assertEquals(InputExecutor.State.OPEN, executor.state());
    }

    @Test
    void activationBeforeImageHandoverStartsReceiverOnce() throws Exception {
        ServerCore core = core(normal());
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(core, core.sessions().currentCode(), normal(),
                new ClientHooks(afterAttach));
        c.start();
        await(afterAttach.reached, "ATTACH to be flushed");
        ServerSession host = core.sessions().current().orElseThrow();
        awaitTrue(c::activationReceived, "SESSION_ACTIVE to arrive");
        assertEquals(ClientPhase.ATTACHING, c.phase(), "the image channel is not handed over yet");
        assertEquals(0, c.framesReceived());
        assertEquals(InputExecutor.State.OPEN, executor.state());
        assertEquals(1, host.inputBeginCount());

        afterAttach.release.countDown();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(1, host.inputBeginCount());
        awaitTrue(() -> host.senderRuns() == 1, "the sender to start");
        c.cancel();
        awaitTerminated(c);
        assertEquals(1, host.inputBeginCount(), "input began exactly once");
        assertEquals(1, host.senderRuns());
    }

    @Test
    void imageHandoverBeforeActivationStartsReceiverOnce() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs.cmdPort(), rs.imgPort(), "123456", normal(), SocketConnector.DEFAULT,
                ClientHooks.none());
        c.start();
        byte[] token = token();
        RawPeer cmd = rawAuthOk(rs, token);
        cmd.send(Packet.screenInfo(1920, 1080));
        awaitTrue(() -> c.screen().isPresent(), "Host geometry reaches the Viewer before activation");
        assertEquals(new Packet.SizePayload(1920, 1080), c.screen().orElseThrow());
        RawPeer img = track(rs.acceptImg());
        Packet attach = img.read();
        assertEquals(PacketType.ATTACH, attach.type());
        assertArrayEquals(token, attach.attachPayload().token());

        awaitTrue(c::imageChannelReady, "the image hand-over");
        assertEquals(ClientPhase.ATTACHING, c.phase(), "SESSION_ACTIVE has not been sent yet");
        assertEquals(0, c.framesReceived());
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, c.phase());
        assertTrue(c.activationReceived());
    }

    @Test
    void cancelBetweenActivationAndHandoverStartsNothing() throws Exception {
        ServerCore core = core(normal());
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(core, core.sessions().currentCode(), normal(), new ClientHooks(afterAttach));
        c.start();
        await(afterAttach.reached, "ATTACH to be flushed");
        ServerSession host = core.sessions().current().orElseThrow();
        awaitTrue(c::activationReceived, "SESSION_ACTIVE to arrive");
        c.cancel();
        afterAttach.release.countDown();
        awaitTerminated(c);
        assertEquals(0, c.framesReceived());
        host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(InputExecutor.State.IDLE, executor.state());
        assertTrue(c.activated().isCompletedExceptionally(), "never became ACTIVE");
        assertEquals(1, host.inputBeginCount());
        host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
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
                        new ClientHooks(afterAttach));
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
        assertEquals(0, c.framesReceived());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(InputExecutor.State.IDLE, executor.state());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void cancelAfterBeginInputBeforeSenderRuns() throws Exception {
        ServerCore core = core(normal());
        HeldHook beforeSender = new HeldHook();
        hostResources.beforeSenderStart = beforeSender;
        ClientSession c = client(core, core.sessions().currentCode(), normal(), ClientHooks.none());
        c.start();
        await(beforeSender.reached, "the sender start point");
        ServerSession host = core.sessions().current().orElseThrow();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, c.phase());
        assertEquals(1, host.inputBeginCount());
        c.cancel();
        beforeSender.release.countDown();
        awaitTerminated(c);
        assertEquals(0, host.senderRuns(), "the sender body must never run after cancel");
        host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void activatedCompletesBeforeSenderStartHook() throws Exception {
        ServerCore core = core(normal());
        HeldHook beforeSender = new HeldHook();
        hostResources.beforeSenderStart = beforeSender;
        ClientSession c = client(core, core.sessions().currentCode(), normal(), ClientHooks.none());
        c.start();
        await(beforeSender.reached, "the sender start point");
        ServerSession host = core.sessions().current().orElseThrow();
        assertEquals(ServerPhase.ACTIVE, host.phase());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(c.activated().isDone());
        assertFalse(c.activated().isCompletedExceptionally());
        beforeSender.release.countDown();
        c.cancel();
        awaitTerminated(c);
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
        assertEquals(0, c.framesReceived());
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
        assertEquals(0, c.framesReceived());
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
        assertEquals(0, c.framesReceived());
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
        ServerCore core = core(normal());
        ClientSession c = client(core, normal());
        c.start();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession host = core.sessions().current().orElseThrow();
        awaitTrue(c::controlEnabled, "the first Viewer frame");
        assertEquals(com.ltm.rd.common.CommandWriter.OfferResult.ACCEPTED, c.offerInput(Packet.keyPress(17)));
        assertTrue(device.awaitCall("keyPress:17"));
        c.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "test connection drop", false));
        assertTrue(device.awaitCall("keyRelease:17"), "the held key must be released");
        host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        host.terminated().get(WAIT_S, TimeUnit.SECONDS);
        awaitTerminated(c);
        assertEquals(1, device.log().stream().filter("keyRelease:17"::equals).count());
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
        ServerCore core = core(normal());
        ClientSession a = client(core, normal());
        a.start();
        a.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession oldHost = core.sessions().current().orElseThrow();
        long oldGeneration = oldHost.generation();
        a.cancel();
        awaitTerminated(a);
        oldHost.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        oldHost.terminated().get(WAIT_S, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the old slot to release");
        ClientSession b = client(core, normal());
        b.start();
        b.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, b.phase());
        a.cancel();
        a.tick(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
        oldHost.close(new CloseInfo(Packet.DisconnectReason.USER, "late close", false));
        oldHost.tick(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
        executor.endSession(oldGeneration).get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, b.phase());
        assertEquals(InputExecutor.State.OPEN, executor.state());
        assertEquals(1, core.sessions().current().orElseThrow().inputBeginCount());
        awaitTrue(b::controlEnabled, "the new Viewer frame");
        assertEquals(com.ltm.rd.common.CommandWriter.OfferResult.ACCEPTED, b.offerInput(Packet.keyPress(17)));
        assertTrue(device.awaitCall("keyPress:17"), "the new session still drives input");
    }

    /** Drives a raw server through the handshake until the client is ACTIVE; returns the command peer. */
    private RawPeer rawActive(RawPeer.Server rs, ClientSession c) throws Exception {
        RawPeer cmd = rawAuthOk(rs, token());
        cmd.send(Packet.screenInfo(1920, 1080));
        RawPeer img = track(rs.acceptImg());
        assertEquals(PacketType.ATTACH, img.read().type());
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        return cmd;
    }

    @Test void activationDeadlineWinsHeldImageHandover() throws Exception {
        ServerCore core = core(relaxed());
        com.ltm.rd.common.FakeClock clock = new com.ltm.rd.common.FakeClock();
        Ticker idle = new Ticker(Duration.ofHours(1), clock);
        HeldHook handover = new HeldHook();
        ClientSession c = new ClientSession(new ClientConfig("127.0.0.1", core.cmdPort(), core.imgPort(),
                core.sessions().currentCode(), relaxed(), clock), generations.incrementAndGet(), SocketConnector.DEFAULT,
                new ClientHooks(handover), idle, scheduler, cleanup);
        clients.add(c);
        try {
            c.start();
            await(handover.reached, "ATTACH flushed before local handover");
            awaitTrue(c::activationReceived, "activation received while handover is held");
            ServerSession host = core.sessions().current().orElseThrow();
            assertEquals(ClientPhase.ATTACHING, c.phase());
            assertFalse(c.imageChannelReady());
            assertEquals(0, c.framesReceived());
            clock.set(relaxed().clientActivation().toNanos());
            handover.release.countDown();
            awaitTerminated(c);
            assertEquals(Packet.DisconnectReason.TIMEOUT, c.lifecycle().closeInfo().orElseThrow().reason());
            assertEquals("activation timeout", c.lifecycle().closeInfo().orElseThrow().message());
            assertFalse(c.imageChannelReady());
            assertEquals(0, c.framesReceived());
            assertTrue(c.activated().isCompletedExceptionally());
            host.terminated().get(WAIT_S, TimeUnit.SECONDS);
            host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        } finally { handover.release.countDown(); c.cancel(); idle.close(); }
    }

    @RepeatedTest(20)
    void cancelRacingAuthResultNeverResurrectsViewer() throws Exception {
        ServerCore core = core(relaxed());
        for (int point = 0; point < 3; point++) {
            String code = core.sessions().currentCode();
            HeldHook beforeAuth = new HeldHook();
            HeldHook beforeHandover = new HeldHook();
            try (RecordingRelay relay = new RecordingRelay(core, false, false)) {
                if (point == 1) relay.holdAuthResult();
                final int stage = point;
                SocketConnector connect = (socket, address, timeout) -> {
                    SocketConnector.DEFAULT.connect(socket, address, timeout);
                    if (stage == 0 && address.getPort() == relay.cmdPort()) beforeAuth.run();
                };
                ClientSession c = client(relay.cmdPort(), relay.imgPort(), code, relaxed(), connect,
                        point == 2 ? new ClientHooks(beforeHandover) : ClientHooks.none());
                relay.bind(c);
                c.start();
                ServerSession host = null;
                if (point == 0) await(beforeAuth.reached, "connected before AUTH");
                else {
                    assertTrue(relay.cmd().await(RecordingRelay.Dir.S2C, PacketType.AUTH_RESULT));
                    host = core.sessions().current().orElseThrow();
                    if (point == 1) assertEquals(ClientPhase.AUTHENTICATING, c.phase());
                    else {
                        await(beforeHandover.reached, "OK won but local activation is still held");
                        awaitTrue(c::activationReceived, "activation packet before cancel");
                        assertEquals(ClientPhase.ATTACHING, c.phase());
                    }
                }
                c.cancel();
                beforeAuth.release.countDown();
                beforeHandover.release.countDown();
                relay.openAuthResultGate();
                awaitTerminated(c);
                assertTrue(c.activated().isCompletedExceptionally());
                assertEquals(0, c.framesReceived());
                if (host != null) {
                    host.terminated().get(WAIT_S, TimeUnit.SECONDS);
                    host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
                    assertTrue(host.inputBeginCount() <= 1);
                }
                awaitTrue(() -> core.sessions().current().isEmpty(), "cancelled Host slot release");
                if (point == 0) assertEquals(code, core.sessions().currentCode());
                else assertNotEquals(code, core.sessions().currentCode());
            } finally { beforeAuth.release.countDown(); beforeHandover.release.countDown(); }
        }
        com.ltm.rd.ui.AppConfig cfg = new com.ltm.rd.ui.AppConfig(InetAddress.getLoopbackAddress(), core.cmdPort(),
                core.imgPort(), "127.0.0.1", relaxed(), System::nanoTime, Duration.ofSeconds(5));
        com.ltm.rd.ui.AppRuntime viewer = com.ltm.rd.ui.AppRuntime.forViewer(cfg);
        try {
            assertEquals(com.ltm.rd.ui.ConnectOutcome.STARTED, viewer.connect("127.0.0.1", core.sessions().currentCode()));
            awaitTrue(() -> viewer.snapshot().phase() == com.ltm.rd.ui.UiPhase.ACTIVE, "ACTIVE wins cancel");
            assertFalse(viewer.cancel());
            assertEquals(com.ltm.rd.ui.UiPhase.ACTIVE, viewer.snapshot().phase());
            assertTrue(viewer.disconnect());
        } finally { viewer.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS); }
    }

    @RepeatedTest(20)
    void imageLossRacingActivationClosesBothPeers() throws Exception {
        ServerCore core = core(relaxed());
        for (boolean lossFirst : new boolean[] {true, false}) {
            CountDownLatch preparing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            releases.add(release);
            hostResources.factory = new com.ltm.rd.host.AdapterFactory() {
                @Override public com.ltm.rd.host.InputDevice inputDevice() { throw new AssertionError(); }
                @Override public void refreshInputDevice() {
                    preparing.countDown();
                    boolean interrupted = false;
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException e) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                @Override public com.ltm.rd.host.ScreenSource newScreenSource() { return new FakeScreenSource(1920, 1080); }
            };
            try (RecordingRelay relay = new RecordingRelay(core, false, false)) {
                ClientSession c = client(relay.cmdPort(), relay.imgPort(), core.sessions().currentCode(), relaxed(),
                        SocketConnector.DEFAULT, ClientHooks.none());
                relay.bind(c);
                c.start();
                assertTrue(preparing.await(WAIT_S, TimeUnit.SECONDS));
                assertTrue(relay.img().await(RecordingRelay.Dir.C2S, PacketType.ATTACH));
                ServerSession host = core.sessions().current().orElseThrow();
                awaitTrue(() -> ServerTestAccess.imageAttached(host), "Host has actually handed over ATTACH before image loss");
                if (lossFirst) {
                    assertEquals(ServerPhase.RESERVED, host.phase());
                    relay.closeImageConnections();
                    release.countDown();
                } else {
                    release.countDown();
                    c.activated().get(WAIT_S, TimeUnit.SECONDS);
                    awaitTrue(c::controlEnabled, "a frame before image loss");
                    assertEquals(com.ltm.rd.common.CommandWriter.OfferResult.ACCEPTED, c.offerInput(Packet.keyPress(17)));
                    assertTrue(device.awaitCall("keyPress:17"));
                    relay.closeImageConnections();
                }
                try { awaitTerminated(c); }
                catch (java.util.concurrent.TimeoutException timeout) {
                    dumpImageLossState(c, host, relay);
                    throw timeout;
                }
                host.terminated().get(WAIT_S, TimeUnit.SECONDS);
                host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
                assertTrue(host.inputBeginCount() <= 1);
                assertTrue(host.senderRuns() <= 1);
                assertFalse(c.controlEnabled());
                assertEquals(ClientPhase.CLOSING, c.phase());
                if (!lossFirst) assertEquals(1, device.log().stream().filter("keyRelease:17"::equals).count());
                awaitTrue(() -> core.sessions().current().isEmpty(), "the image loss closes the whole Host session");
            } finally { release.countDown(); }
        }
        hostResources.factory = null;
    }

    private static void dumpImageLossState(ClientSession viewer, ServerSession host, RecordingRelay relay) {
        System.err.println("image-loss state viewer=" + viewer.phase() + ", close=" + viewer.lifecycle().closeInfo()
                + ", sockets=" + viewer.socketsClosed().isDone() + ", terminated=" + viewer.terminated().isDone()
                + ", input=" + viewer.inputReleased().isDone() + ", workers=" + viewer.lifecycle().liveWorkers());
        System.err.println("image-loss state host=" + host.phase() + ", close=" + host.closeInfo()
                + ", sockets=" + host.socketsClosed().isDone() + ", terminated=" + host.terminated().isDone()
                + ", input=" + host.inputReleased().isDone() + ", image=" + ServerTestAccess.imageAttached(host)
                + ", relay=" + relay.imageState());
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (!thread.getName().startsWith("rd-")) return;
            System.err.println(thread.getName() + " " + thread.getState());
            for (StackTraceElement frame : stack) System.err.println("  " + frame);
        });
    }

    @Test void relayRecordBeforeAttachForwardIsNotHostHandover() throws Exception {
        com.ltm.rd.common.FakeClock hostClock = new com.ltm.rd.common.FakeClock();
        ServerCore core = hostResources.core(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0,
                relaxed(), hostClock, new SecureRandom()));
        cores.add(core);
        core.start();
        try (RecordingRelay relay = new RecordingRelay(core, false, false)) {
            relay.holdAttachForward();
            ClientSession c = client(relay.cmdPort(), relay.imgPort(), core.sessions().currentCode(), relaxed(),
                    SocketConnector.DEFAULT, ClientHooks.none());
            relay.bind(c);
            c.start();
            assertTrue(relay.img().await(RecordingRelay.Dir.C2S, PacketType.ATTACH));
            ServerSession host = core.sessions().current().orElseThrow();
            awaitTrue(() -> host.screen().isPresent(), "Host preparation completed without image handover");
            assertFalse(ServerTestAccess.imageAttached(host));
            relay.closeImageConnections();
            relay.openAttachForwardGate();
            assertEquals(ServerPhase.RESERVED, host.phase());
            assertEquals(ClientPhase.ATTACHING, c.phase());
            assertFalse(host.socketsClosed().isDone());
            assertFalse(c.socketsClosed().isDone());
            assertFalse(host.terminated().isDone());
            assertFalse(c.terminated().isDone());
            assertEquals(0, host.inputBeginCount());
            System.out.println("held-forward reproduction: Host RESERVED, Viewer ATTACHING, Host image=false, " + relay.imageState());
            long deadline = host.reservedAtNanos() + relaxed().reserve().toNanos();
            hostClock.set(deadline);
            host.tick(deadline);
            awaitTerminated(c);
            host.terminated().get(WAIT_S, TimeUnit.SECONDS);
            host.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
            assertEquals(Packet.DisconnectReason.TIMEOUT, host.closeInfo().orElseThrow().reason());
            assertEquals("attach timeout", host.closeInfo().orElseThrow().message());
            assertEquals(0, host.inputBeginCount());
        }
    }
}
