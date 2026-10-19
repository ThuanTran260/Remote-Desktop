package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.it.RawPeer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Handshake and shutdown tests that need the package-private seams of ServerCore: ordering races
 * forced with latches, thread-start failure, a stalled ticker and shutdown edge cases.
 */
@Timeout(120)
class ServerSeamIntegrationTest {
    private static final int WAIT_S = 10;
    private static final long SLACK_MS = 600;

    private final List<ServerCore> cores = new ArrayList<>();
    private final List<RawPeer> peers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        peers.forEach(RawPeer::close);
        cores.forEach(ServerCore::close);
    }

    private static Timeouts timeouts(Duration handshake, Duration reserve) {
        return new Timeouts(handshake, reserve, Duration.ofSeconds(5), Duration.ofSeconds(7),
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(3),
                Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ZERO, Duration.ofSeconds(30),
                Duration.ofMillis(100));
    }

    private ServerCore core(Timeouts t, ServerCore.Seams seams) throws IOException {
        ServerCore c = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, t, System::nanoTime,
                new SecureRandom()), seams);
        cores.add(c);
        c.start();
        return c;
    }

    private RawPeer connect(int port) throws IOException {
        RawPeer p = RawPeer.connect(port);
        peers.add(p);
        return p;
    }

    private record Authed(RawPeer cmd, byte[] token) {}

    /** AUTH with the current code; returns the command peer and the token. */
    private Authed auth(ServerCore c) throws IOException {
        RawPeer cmd = connect(c.cmdPort());
        cmd.send(Packet.auth(c.sessions().currentCode()));
        Packet.AuthResultPayload result = cmd.read().authResultPayload();
        assertEquals(Packet.AuthStatus.OK, result.status());
        return new Authed(cmd, result.token());
    }

    private static long awaitEndMs(RawPeer peer, long startNanos) {
        try {
            while (peer.in.read() >= 0) {
                // discard
            }
        } catch (SocketTimeoutException e) {
            fail("peer never closed the connection");
        } catch (IOException e) {
            // reset counts as closed
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static Runnable holding(CountDownLatch reached, CountDownLatch release) {
        return () -> {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // shutdown interrupts pool threads: carry on so they can end
            }
        };
    }

    // ---- hand-over versus shutdown, both orders --------------------------------------------

    @Test
    void handOverWinsWhenShutdownStartsInsideTheSessionTransaction() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerCore c = core(timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5)),
                new ServerCore.Seams(null, holding(reached, release), null, false));
        Authed a = auth(c);
        ServerSession s = c.sessions().current().orElseThrow();
        RawPeer img = connect(c.imgPort());
        img.send(Packet.attach(a.token()));
        assertTrue(reached.await(WAIT_S, TimeUnit.SECONDS), "attach is inside the transaction, after the token check");

        Thread closer = new Thread(c::close, "test-closer");
        closer.setDaemon(true);
        closer.start();
        awaitTrue(() -> c.sessions().shuttingDown(), "shutdown to begin"); // close() now waits for the session lock
        release.countDown(); // the hand-over proceeds first, then the close takes the lock
        closer.join(WAIT_S * 1000L);
        assertFalse(closer.isAlive(), "close() must finish");

        assertTrue(s.lifecycle().isClosed());
        awaitEndMs(img, System.nanoTime()); // the handed-over socket is closed by the session
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
    }

    @Test
    void shutdownWinsWhenItCompletesBeforeTheAttachIsEvaluated() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerCore c = core(timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5)),
                new ServerCore.Seams(null, null, holding(reached, release), false));
        Authed a = auth(c);
        ServerSession s = c.sessions().current().orElseThrow();
        RawPeer img = connect(c.imgPort());
        img.send(Packet.attach(a.token()));
        assertTrue(reached.await(WAIT_S, TimeUnit.SECONDS), "the handler is paused before attachImage, outside any lock");

        c.close(); // runs to completion first: the session is closed before the attach is evaluated
        assertTrue(s.lifecycle().isClosed());
        release.countDown();
        awaitEndMs(img, System.nanoTime()); // the late ATTACH is rejected and its socket closed
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        assertEquals(ServerPhase.CLOSING, s.phase(), "the session never became attached or active");
    }

    @Test
    void readerStartFailureClosesSession() throws Exception {
        ServerCore.Seams seams = new ServerCore.Seams(r -> new Thread(r) {
            @Override
            public synchronized void start() {
                if (getName().equals("rd-cmd-reader")) throw new IllegalStateException("cannot start reader");
                super.start();
            }
        }, null, null, false);
        ServerCore c = core(timeouts(Duration.ofSeconds(1), Duration.ofMillis(500)), seams);
        String oldCode = c.sessions().currentCode();
        Authed a = auth(c);
        ServerSession s = c.sessions().current().orElseThrow();
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        CloseInfo info = s.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("cannot start worker", info.message());
        assertTrue(c.sessions().current().isEmpty(), "the slot is freed");
        assertNotEquals(oldCode, c.sessions().currentCode());
        assertNotNull(a);
    }

    // ---- soTimeout fallback, shutdown ------------------------------------------------------

    @Test
    void stalledTickerStillEndsASilentPendingConnection() throws Exception {
        ServerCore c = core(timeouts(Duration.ofMillis(400), Duration.ofSeconds(5)),
                new ServerCore.Seams(null, null, null, true)); // pending connections are never swept
        long start = System.nanoTime();
        RawPeer silent = connect(c.cmdPort());
        long ms = awaitEndMs(silent, start);
        assertTrue(ms >= 400 && ms <= 400 + SLACK_MS, "closed by the soTimeout fallback: " + ms + " ms");
    }

    @Test
    void closeWithPendingConnectionsFinishesQuickly() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), ServerCore.Seams.none());
        List<RawPeer> silent = new ArrayList<>();
        for (int i = 0; i < 6; i++) silent.add(connect(c.cmdPort())); // running and queued handshakes
        long start = System.nanoTime();
        c.close();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 1000, "close() took " + ms + " ms");
        for (RawPeer p : silent) awaitEndMs(p, System.nanoTime()); // every pending socket was closed
    }

    @Test
    void startAfterCloseIsRefused() throws Exception {
        ServerCore c = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0,
                timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1)), System::nanoTime, new SecureRandom()));
        cores.add(c);
        c.close();
        assertThrows(IllegalStateException.class, c::start);
    }
}
