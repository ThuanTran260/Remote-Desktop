package com.ltm.rd.client;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
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
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), executor, screen, connector, hooks,
                idleTicker, scheduler, cleanup);
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
        cmd.readUntil(PacketType.SCREEN_INFO);
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

        assertEquals(0, c.releaseAllReceived());
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
        assertEquals(0, c.inputBeginCount());
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
        assertEquals(0, c.inputBeginCount());
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
        assertEquals(0, c.inputBeginCount());
        assertTrue(c.authFailure().isEmpty());
    }

    @Test
    void cancelBetweenAuthResultAndCommandChannelHandOverStartsNothing() throws Exception {
        RawPeer.Server rs = rawServer();
        GatedGeometry screen = new GatedGeometry();
        AtomicInteger connects = new AtomicInteger();
        SocketConnector counting = (socket, address, timeoutMs) -> {
            connects.incrementAndGet();
            SocketConnector.DEFAULT.connect(socket, address, timeoutMs);
        };
        ClientSession c = client(rs, counting, ClientHooks.none(), screen);
        c.start();
        RawPeer cmd = acceptAuth(rs);
        cmd.send(Packet.authResult(Packet.AuthStatus.OK, token()));
        assertTrue(screen.reached.await(WAIT_S, TimeUnit.SECONDS), "the client read AUTH_RESULT and asks for the geometry");

        c.cancel();
        screen.release.countDown();

        awaitTerminated(c);
        assertEquals(1, connects.get(), "no image connection after the cancel");
        assertEquals("cancelled", closeInfo(c).message());
        assertEquals(0, c.inputBeginCount());
        assertFalse(c.activationReceived());
        assertThrows(IOException.class, cmd::read, "no SCREEN_INFO (or anything else) was sent");
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
        assertEquals(0, c.inputBeginCount());
    }

    // ---- SESSION_ACTIVE out of turn ------------------------------------------------------------------

    @Test
    void secondSessionActiveWhileAttachingIsAProtocolViolation() throws Exception {
        RawPeer.Server rs = rawServer();
        HeldHook afterAttach = new HeldHook();
        ClientSession c = client(rs, SocketConnector.DEFAULT, new ClientHooks(afterAttach, null),
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
        assertEquals(0, c.inputBeginCount());
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
        assertEquals(1, c.inputBeginCount(), "input began once and is released on close");
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void refusedInputSessionClosesWithoutBecomingActive() throws Exception {
        assertTrue(executor.beginSession(1_000_000), "another session already owns the input"); // client gets 1
        RawPeer.Server rs = rawServer();
        ClientSession c = client(rs);
        c.start();
        RawPeer cmd = attaching(rs);

        cmd.send(Packet.sessionActive()); // both start conditions are met; the executor says no

        awaitTerminated(c);
        CloseInfo info = closeInfo(c);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("input session refused", info.message());
        assertTrue(info.sendDisconnect());
        assertTrue(c.activated().isCompletedExceptionally(), "never became ACTIVE");
        assertEquals(0, c.inputBeginCount());
        assertEquals(0, c.senderRuns());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        Packet disconnect = cmd.read();
        assertEquals(PacketType.DISCONNECT, disconnect.type());
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, disconnect.disconnectPayload().reason());
        executor.endSession(1_000_000).get(WAIT_S, TimeUnit.SECONDS);
    }
}
