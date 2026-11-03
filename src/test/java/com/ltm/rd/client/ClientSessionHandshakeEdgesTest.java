package com.ltm.rd.client;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.host.ScreenGeometry;
import com.ltm.rd.host.ScreenSource;
import com.ltm.rd.it.RawPeer;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The failure and defensive branches of {@link ClientSession}'s handshake, driven against a raw
 * packet-level server so that each test says exactly what the server sends and when. The client's
 * clock is fake and frozen and its ticker never ticks on its own, so no deadline can fire unless a
 * test calls {@code tick} itself; every ordering is forced with a latch.
 */
@Timeout(120)
class ClientSessionHandshakeEdgesTest {
    private static final int WAIT_S = 10;
    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(300), Duration.ofSeconds(3),
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
    private final FakeClock clock = new FakeClock();
    private final Ticker idleTicker = new Ticker(Duration.ofHours(1), clock); // never ticks within a test
    private final FakeInputDevice device = new FakeInputDevice();
    private final InputExecutor executor = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 256,
            InputExecutor.defaultThreadFactory(), t -> {});
    private final List<ClientSession> clients = new ArrayList<>();
    private final List<RawPeer.Server> rawServers = new ArrayList<>();
    private final List<RawPeer> peers = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final AtomicLong generations = new AtomicLong();

    @AfterEach
    void cleanup() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        for (ClientSession c : clients) c.cancel();
        for (RawPeer p : peers) p.close();
        for (RawPeer.Server s : rawServers) s.close();
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        idleTicker.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    // ---- fixtures --------------------------------------------------------------------------

    private RawPeer.Server rawServer() throws IOException {
        RawPeer.Server s = new RawPeer.Server();
        rawServers.add(s);
        return s;
    }

    private RawPeer track(RawPeer p) {
        peers.add(p);
        return p;
    }

    private ClientSession client(RawPeer.Server rs, SocketConnector connector, ClientHooks hooks,
            ScreenSource screen) {
        ClientConfig cfg = new ClientConfig("127.0.0.1", rs.cmdPort(), rs.imgPort(), "123456", TIMEOUTS, clock);
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), connector, hooks, idleTicker, scheduler, cleanup);
        clients.add(c);
        return c;
    }

    private ClientSession client(RawPeer.Server rs) {
        return client(rs, SocketConnector.DEFAULT, ClientHooks.none(), new FakeScreenSource(1920, 1080));
    }

    private static byte[] token() {
        byte[] t = new byte[16];
        for (int i = 0; i < t.length; i++) t[i] = (byte) (i + 1);
        return t;
    }

    /** Accepts the command connection and reads AUTH: the client is now AUTHENTICATING. */
    private RawPeer acceptAuth(RawPeer.Server rs) throws IOException {
        RawPeer cmd = track(rs.acceptCmd());
        assertEquals(PacketType.AUTH, cmd.read().type());
        return cmd;
    }

    /** Drives the server through AUTH_RESULT(OK), SCREEN_INFO and ATTACH; the client is then ATTACHING. */
    private RawPeer attaching(RawPeer.Server rs) throws IOException {
        RawPeer cmd = acceptAuth(rs);
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));
        cmd.send(Packet.screenInfo(1920, 1080));
        assertEquals(PacketType.ATTACH, track(rs.acceptImg()).read().type());
        return cmd;
    }

    private static void awaitTerminated(ClientSession c) throws Exception {
        c.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, c.lifecycle().liveWorkers());
    }

    private static CloseInfo closeInfo(ClientSession c) {
        return c.lifecycle().closeInfo().orElseThrow();
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

    /** Reports the geometry only once released, so a test can act between AUTH_RESULT and the hand-over. */
    private final class GatedGeometry implements ScreenSource {
        private final FakeScreenSource delegate = new FakeScreenSource(1920, 1080);
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GatedGeometry() {
            releases.add(release);
        }

        @Override
        public ScreenGeometry geometry() {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // the close procedure interrupts workers: carry on so the thread can end
            }
            return delegate.geometry();
        }

        @Override
        public BufferedImage capture(Rectangle bounds) throws IOException {
            return delegate.capture(bounds);
        }
    }

    // ---- start and early ticks -----------------------------------------------------------------

    @Test void coalescedAuthResultAndScreenInfoReusesInputStream() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = acceptAuth(rs);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream packets = new java.io.DataOutputStream(bytes);
        com.ltm.rd.common.PacketIO.write(packets, Packet.authResult(Packet.AuthStatus.OK, token()));
        com.ltm.rd.common.PacketIO.write(packets, Packet.screenInfo(1920, 1080));
        cmd.socket.getOutputStream().write(bytes.toByteArray());
        cmd.socket.getOutputStream().flush();
        cmd.send(Packet.sessionActive());
        assertDoesNotThrow(() -> c.activated().get(WAIT_S, TimeUnit.SECONDS));
        RawPeer img = track(rs.acceptImg());
        assertEquals(PacketType.ATTACH, img.read().type());
        assertEquals(ClientPhase.ACTIVE, c.phase());
    }

    @Test void activationDeadlineIncludesCommandHandoverLockWait() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession viewer = client(rs);
        boolean authenticating = viewer.lifecycle().atomically(tx -> tx.advance(ClientPhase.CONNECTING, ClientPhase.AUTHENTICATING));
        assertTrue(authenticating);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var entered = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try (var input = new java.io.PipedInputStream(); var peer = new java.io.PipedOutputStream(input)) {
            var socket = viewer.lifecycle().register(new java.net.Socket());
            var channel = new com.ltm.rd.common.Channel(socket, new java.io.DataInputStream(input),
                    new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()));
            var handedOver = viewer.lifecycle().atomically(tx -> {
                var result = caller.submit(() -> {
                    worker.set(Thread.currentThread());
                    entered.countDown();
                    return viewer.attachCommandChannel(channel);
                });
                try { assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
                while (worker.get().getState() != Thread.State.WAITING) {
                    assertTrue(System.nanoTime() < bound, "handover must wait for the lifecycle lock");
                    Thread.onSpinWait();
                }
                clock.set(TIMEOUTS.clientActivation().toNanos());
                return result;
            });
            assertTrue(handedOver.get(WAIT_S, TimeUnit.SECONDS));
            viewer.tick(clock.getAsLong());
            assertTrue(viewer.lifecycle().isClosed(), "lock contention is part of the activation budget from AUTH OK");
            assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(viewer).reason());
            assertEquals("activation timeout", closeInfo(viewer).message());
            awaitTerminated(viewer);
        } finally { caller.shutdownNow(); }
    }

    @Test
    void startingTwiceConnectsOnce() throws Exception {
        RawPeer.Server rs = rawServer();
        AtomicInteger connects = new AtomicInteger();
        SocketConnector counting = (socket, address, timeoutMs) -> {
            connects.incrementAndGet();
            SocketConnector.DEFAULT.connect(socket, address, timeoutMs);
        };
        ClientSession c = client(rs, counting, ClientHooks.none(), new FakeScreenSource(1920, 1080));

        assertTrue(c.start());
        assertFalse(c.start(), "a session starts at most once");
        acceptAuth(rs);
        c.cancel();
        awaitTerminated(c);

        assertEquals(1, connects.get(), "the second start() did not open another connection");
    }

    @Test
    void releaseAllReceivedIsZeroBeforeTheCommandChannelExists() throws Exception {
        ClientSession c = client(rawServer());

        assertEquals(0, c.releaseAllSent());
    }

    @Test
    void tickDecidesNothingBeforeTheHandshakeStartedOrBeforeTheDeadline() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);

        c.tick(Long.MAX_VALUE / 2); // not started: no deadline is armed, however late the tick
        assertEquals(ClientPhase.CONNECTING, c.phase());
        assertTrue(c.lifecycle().closeInfo().isEmpty());

        c.start();
        acceptAuth(rs); // AUTHENTICATING with the deadline at 0 + clientAuth (10 s)
        c.tick(s(9));
        assertEquals(ClientPhase.AUTHENTICATING, c.phase(), "one second before the deadline");
        assertTrue(c.lifecycle().closeInfo().isEmpty());

        c.tick(s(10));
        awaitTerminated(c);
        assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(c).reason());
        assertEquals("auth timeout", closeInfo(c).message());
    }

    // ---- what the server answers instead of AUTH_RESULT(OK) -----------------------------------------

    @Test
    void serverClosingWithoutAuthResultClosesWithServerClosedConnection() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = acceptAuth(rs);

        cmd.close(); // no reply at all

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("server closed connection", info.message());
        assertFalse(info.sendDisconnect());
        assertTrue(c.authFailure().isEmpty(), "no AUTH_RESULT, so no auth failure to report");
    }

    @Test
    void packetOtherThanAuthResultIsAProtocolViolation() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = acceptAuth(rs);

        cmd.send(Packet.sessionActive()); // only AUTH_RESULT is allowed here

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("protocol violation", info.message());
        assertEquals(0, c.framesReceived());
    }

    @Test
    void authResultCutOffMidPacketIsAHandshakeFailure() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = acceptAuth(rs);

        cmd.out.writeByte(0x02); // AUTH_RESULT announces 17 payload bytes ...
        cmd.out.writeInt(17);
        cmd.out.write(new byte[5]); // ... and delivers five
        cmd.out.flush();
        cmd.close();

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("handshake failed", info.message());
        assertEquals(0, c.framesReceived());
    }

    // ---- cancelled while a step of the handshake is in progress ---------------------------------------

    @Test
    void connectThatReturnsAfterCancelDoesNotAdvanceTheHandshake() throws Exception {
        RawPeer.Server rs = rawServer();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        AtomicInteger connects = new AtomicInteger();
        SocketConnector slow = (socket, address, timeoutMs) -> {
            connects.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // cancel interrupts the worker: still return normally, as a connect that just finished would
            }
        };
        ClientSession c = client(rs, slow, ClientHooks.none(), new FakeScreenSource(1920, 1080));
        c.start();
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS));

        c.cancel();
        release.countDown();

        awaitTerminated(c);
        assertEquals(1, connects.get(), "after the cancel the handshake went no further");
        assertEquals(Packet.DisconnectReason.USER, closeInfo(c).reason());
        assertEquals("cancelled", closeInfo(c).message());
        assertEquals(0, c.framesReceived());
        assertTrue(c.authFailure().isEmpty());
    }

    @Test
    void cancelDuringImageConnectNeverActivates() throws Exception {
        RawPeer.Server rs = rawServer();
        HeldHook beforeImageConnect = new HeldHook();
        AtomicInteger connects = new AtomicInteger();
        SocketConnector counting = (socket, address, timeoutMs) -> {
            connects.incrementAndGet();
            if (address.getPort() == rs.imgPort()) beforeImageConnect.run();
            SocketConnector.DEFAULT.connect(socket, address, timeoutMs);
        };
        ClientSession c = client(rs, counting, ClientHooks.none(), new FakeScreenSource(1920, 1080));
        c.start();
        RawPeer cmd = acceptAuth(rs);
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));
        assertTrue(beforeImageConnect.reached.await(WAIT_S, TimeUnit.SECONDS));
        // Fence actual command-writer execution before expecting its final DISCONNECT.
        cmd.send(Packet.ping(73));
        assertEquals(73, cmd.readUntil(PacketType.PONG).seq());
        c.cancel();
        beforeImageConnect.release.countDown();
        awaitTerminated(c);
        assertEquals(2, connects.get(), "both registered channel attempts are accounted for before cancellation");
        assertEquals("cancelled", closeInfo(c).message());
        assertFalse(c.activationReceived());
        assertEquals(0, c.framesReceived());
        assertEquals(Packet.DisconnectReason.USER, cmd.readUntil(PacketType.DISCONNECT).disconnectPayload().reason());
    }

    // ---- image channel ---------------------------------------------------------------------------

    @Test
    void imageConnectFailureClosesWithConnectFailed() throws Exception {
        RawPeer.Server rs = rawServer();
        SocketConnector imageRefused = (socket, address, timeoutMs) -> {
            if (address.getPort() == rs.imgPort()) throw new IOException("image port unreachable");
            SocketConnector.DEFAULT.connect(socket, address, timeoutMs);
        };
        ClientSession c = client(rs, imageRefused, ClientHooks.none(), new FakeScreenSource(1920, 1080));
        c.start();
        RawPeer cmd = acceptAuth(rs);

        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("connect failed", info.message());
        assertFalse(info.sendDisconnect());
        assertTrue(c.activated().isCompletedExceptionally());
        assertEquals(0, c.framesReceived());
    }

    // ---- SESSION_ACTIVE out of turn ------------------------------------------------------------------

    @Test
    void v1CloseMessagesSurviveTheRulesTable() throws Exception {
        RawPeer.Server pendingServer = rawServer();
        ClientSession pending = client(pendingServer);
        pending.start();
        RawPeer pendingCmd = attaching(pendingServer);
        assertEquals(ClientPhase.ATTACHING, pending.phase());

        pendingCmd.send(Packet.keyPress(65));

        awaitTerminated(pending);
        CloseInfo pendingInfo = closeInfo(pending);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, pendingInfo.reason());
        assertEquals("protocol violation", pendingInfo.message());
        assertTrue(pendingInfo.sendDisconnect());
        assertEquals(0, pending.framesReceived());
        Packet pendingDisconnect = pendingCmd.readUntil(PacketType.DISCONNECT);
        assertEquals("protocol violation", pendingDisconnect.disconnectPayload().message());

        RawPeer.Server activeServer = rawServer();
        ClientSession active = client(activeServer);
        active.start();
        RawPeer activeCmd = attaching(activeServer);
        activeCmd.send(Packet.sessionActive());
        active.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, active.phase());

        activeCmd.send(Packet.sessionActive());

        awaitTerminated(active);
        CloseInfo activeInfo = closeInfo(active);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, activeInfo.reason());
        assertEquals("unexpected SESSION_ACTIVE", activeInfo.message());
        assertTrue(activeInfo.sendDisconnect());
        assertTrue(active.activationReceived());
        Packet activeDisconnect = activeCmd.readUntil(PacketType.DISCONNECT);
        assertEquals("unexpected SESSION_ACTIVE", activeDisconnect.disconnectPayload().message());
    }

    @Test
    void neverValidCommandTypeIsRejectedByTheReader() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = attaching(rs);

        cmd.send(Packet.keyPress(65));

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("protocol violation", info.message());
        assertTrue(info.sendDisconnect());
        assertEquals(0, c.framesReceived());
    }

    @Test
    void secondSessionActiveWhileAttachingIsAProtocolViolation() throws Exception {
        RawPeer.Server rs = rawServer();
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(rs, SocketConnector.DEFAULT, new ClientHooks(afterAttach),
                new FakeScreenSource(1920, 1080));
        c.start();
        RawPeer cmd = attaching(rs);
        assertTrue(afterAttach.reached.await(WAIT_S, TimeUnit.SECONDS), "ATTACH is flushed, the image hand-over is held");

        cmd.send(Packet.sessionActive()); // the first one is accepted but cannot start anything yet
        cmd.send(Packet.sessionActive()); // a second one is not

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SESSION_ACTIVE", info.message());
        assertTrue(info.sendDisconnect());
        assertTrue(c.activationReceived(), "the first SESSION_ACTIVE was taken");
        assertEquals(0, c.framesReceived());
        Packet disconnect = cmd.read();
        assertEquals(PacketType.DISCONNECT, disconnect.type());
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, disconnect.disconnectPayload().reason());
    }

    @Test
    void sessionActiveWhileAlreadyActiveIsAProtocolViolation() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = attaching(rs);
        cmd.send(Packet.sessionActive());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(ClientPhase.ACTIVE, c.phase());

        cmd.send(Packet.sessionActive());

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SESSION_ACTIVE", info.message());
        assertTrue(c.activationReceived(), "activation was accepted before the duplicate closed the Viewer");
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }


    private static Packet packet(com.ltm.rd.common.PacketType type) {
        return switch (type) {
            case AUTH -> Packet.auth("123456");
            case AUTH_RESULT -> Packet.authResult(Packet.AuthStatus.OK, new byte[16]);
            case ATTACH -> Packet.attach(new byte[16]);
            case SCREEN_INFO -> Packet.screenInfo(1920, 1080);
            case SESSION_ACTIVE -> Packet.sessionActive();
            case FRAME -> Packet.frame(1, 1, new byte[] {1});
            case MOUSE_MOVE -> Packet.mouseMove(1, 1);
            case MOUSE_PRESS -> Packet.mousePress(1, 1, 1);
            case MOUSE_RELEASE -> Packet.mouseRelease(1, 1, 1);
            case WHEEL -> Packet.wheel(1);
            case KEY_PRESS -> Packet.keyPress(65);
            case KEY_RELEASE -> Packet.keyRelease(65);
            case RELEASE_ALL -> Packet.releaseAll();
            case PING -> Packet.ping(1);
            case PONG -> Packet.pong(1);
            case DISCONNECT -> Packet.disconnect(Packet.DisconnectReason.USER, "test");
        };
    }

    private static com.ltm.rd.common.Channel idleChannel() throws IOException {
        java.io.PipedInputStream in = new java.io.PipedInputStream();
        new java.io.PipedOutputStream(in);
        return new com.ltm.rd.common.Channel(new java.net.Socket(), new java.io.DataInputStream(in),
                new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()));
    }

    @Test void viewerRejectsEveryDisallowedCommandType() throws Exception {
        var pending = java.util.EnumSet.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE,
                PacketType.PING, PacketType.PONG, PacketType.DISCONNECT);
        var active = java.util.EnumSet.of(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT);
        for (boolean activated : new boolean[] {false, true}) {
            for (PacketType type : PacketType.values()) {
                if ((activated ? active : pending).contains(type)) continue;
                ClientSession c = new ClientSession(new ClientConfig("127.0.0.1", 1, 2, "123456", TIMEOUTS, clock),
                        generations.incrementAndGet(), SocketConnector.DEFAULT, ClientHooks.none(), idleTicker, scheduler, cleanup);
                clients.add(c);
                c.lifecycle().atomically(tx -> tx.advance(ClientPhase.CONNECTING, ClientPhase.AUTHENTICATING));
                var command = idleChannel();
                c.lifecycle().register(command.socket());
                c.lifecycle().addStopHook(command::closeWrappersQuietly);
                assertTrue(c.attachCommandChannel(command));
                if (activated) {
                    c.dispatch(Packet.screenInfo(1920, 1080));
                    var image = idleChannel();
                    c.lifecycle().register(image.socket());
                    c.lifecycle().addStopHook(image::closeWrappersQuietly);
                    c.attachImageChannel(image);
                    c.dispatch(Packet.sessionActive());
                    assertEquals(ClientPhase.ACTIVE, c.phase());
                }
                c.dispatch(packet(type));
                assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, closeInfo(c).reason(), type.name());
                assertEquals(ClientPhase.CLOSING, c.phase());
                assertEquals(0, c.framesReceived());
                awaitTerminated(c);
            }
        }
    }

    @Test void secondScreenInfoWhileAttachingCloses() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = attaching(rs);
        cmd.send(Packet.screenInfo(1280, 720));
        awaitTerminated(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, closeInfo(c).reason());
        assertEquals("unexpected SCREEN_INFO", closeInfo(c).message());
        assertEquals(new Packet.SizePayload(1920, 1080), c.screen().orElseThrow());
        assertEquals(0, c.framesReceived());
    }

    @Test void sessionActiveBeforeScreenInfoCloses() throws Exception {
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = acceptAuth(rs);
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));
        RawPeer img = track(rs.acceptImg());
        assertEquals(PacketType.ATTACH, img.read().type());
        img.send(Packet.frame(1, 1, new byte[] {1}));
        cmd.send(Packet.sessionActive());
        awaitTerminated(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, closeInfo(c).reason());
        assertEquals("unexpected SESSION_ACTIVE", closeInfo(c).message());
        assertFalse(c.activationReceived());
        assertTrue(c.activated().isCompletedExceptionally());
        assertEquals(0, c.framesReceived());
    }

    @Test void v1ServerBadVersionReturnsIdle() throws Exception {
        RawPeer.Server rs = rawServer();
        var cfg = new com.ltm.rd.ui.AppConfig(java.net.InetAddress.getLoopbackAddress(), rs.cmdPort(), rs.imgPort(),
                "127.0.0.1", TIMEOUTS, clock, Duration.ofSeconds(5));
        var viewer = com.ltm.rd.ui.AppRuntime.forViewer(cfg);
        try {
            assertEquals(com.ltm.rd.ui.ConnectOutcome.STARTED, viewer.connect("127.0.0.1", "123456"));
            RawPeer cmd = track(rs.acceptCmd());
            assertEquals(2, cmd.read().authPayload().version());
            cmd.send(Packet.authResult(Packet.AuthStatus.BAD_VERSION, new byte[16]));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
            while (viewer.snapshot().phase() != com.ltm.rd.ui.UiPhase.IDLE) {
                assertTrue(System.nanoTime() < deadline);
                Thread.onSpinWait();
            }
            assertEquals("Phiên bản khác nhau. Hãy dùng cùng một bản jar trên hai máy.", viewer.snapshot().message());
        } finally {
            assertEquals(com.ltm.rd.ui.InputRelease.NOT_NEEDED, viewer.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS).inputRelease());
        }
    }
}
