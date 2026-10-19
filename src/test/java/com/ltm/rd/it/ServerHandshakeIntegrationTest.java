package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerPhase;
import com.ltm.rd.server.ServerSession;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Server handshake over real loopback sockets with a raw client built on PacketIO. Timeout tests
 * assert the exact close cause, the decision window [deadline, deadline + 600 ms], the socket
 * close separately and terminated() with its own wait.
 */
@Timeout(120)
class ServerHandshakeIntegrationTest {
    private static final int WAIT_S = 10;
    private static final long SLACK_MS = 600;
    private static final Duration GRACE = Duration.ofMillis(300);

    private final List<ServerCore> cores = new ArrayList<>();
    private final List<Raw> raws = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Raw r : raws) r.close();
        for (ServerCore c : cores) c.close();
    }

    // ---- fixtures --------------------------------------------------------------------------

    private static Timeouts timeouts(Duration reserve) {
        return new Timeouts(Duration.ofMillis(300), reserve, Duration.ofSeconds(5), Duration.ofSeconds(7),
                Duration.ofMillis(200), Duration.ofMillis(800), Duration.ofSeconds(10), Duration.ofSeconds(3),
                Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(10), GRACE,
                Duration.ofSeconds(3), Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));
    }

    private static Timeouts fast() {
        return timeouts(Duration.ofMillis(500));
    }

    private static ServerConfig config(Timeouts t, int cmdPort, int imgPort) {
        return new ServerConfig(InetAddress.getLoopbackAddress(), cmdPort, imgPort, t, System::nanoTime,
                new SecureRandom());
    }

    private ServerCore core(Timeouts t) throws IOException {
        return core(t, ServerCore.Seams.none());
    }

    private ServerCore core(Timeouts t, ServerCore.Seams seams) throws IOException {
        ServerCore c = new ServerCore(config(t, 0, 0), seams);
        cores.add(c);
        c.start();
        return c;
    }

    /** Raw protocol client: speaks PacketIO directly, no session logic. */
    private final class Raw implements AutoCloseable {
        final Socket socket = new Socket();
        final DataInputStream in;
        final DataOutputStream out;

        Raw(int port) throws IOException {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
            socket.setSoTimeout(WAIT_S * 1000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void send(Packet p) throws IOException {
            PacketIO.write(out, p);
            out.flush();
        }

        Packet read() throws IOException {
            return PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class))
                    .orElseThrow(() -> new EOFException("peer closed"));
        }

        /** Reads until a packet of {@code type} arrives, skipping PING/PONG. */
        Packet readUntil(PacketType type) throws IOException {
            while (true) {
                Packet p = read();
                if (p.type() == type) return p;
                assertTrue(p.type() == PacketType.PING || p.type() == PacketType.PONG,
                        "unexpected " + p.type() + " while waiting for " + type);
            }
        }

        /** Reads packets until the peer closes; returns what arrived. Fails if it never closes. */
        List<Packet> drain() {
            List<Packet> got = new ArrayList<>();
            try {
                while (true) {
                    var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class));
                    if (p.isEmpty()) return got;
                    got.add(p.get());
                }
            } catch (SocketTimeoutException e) {
                fail("peer never closed the connection");
                return got;
            } catch (IOException e) {
                return got; // reset or garbage after close: the connection is over
            }
        }

        /** Blocks until EOF/reset and returns the elapsed milliseconds since {@code startNanos}. */
        long awaitEndMs(long startNanos) {
            try {
                while (in.read() >= 0) {
                    // discard
                }
            } catch (SocketTimeoutException e) {
                fail("peer never closed the connection");
            } catch (IOException e) {
                // connection reset counts as closed
            }
            return ms(System.nanoTime() - startNanos);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // test teardown
            }
        }
    }

    private Raw raw(int port) throws IOException {
        Raw r = new Raw(port);
        raws.add(r);
        return r;
    }

    private record Authed(Raw cmd, byte[] token) {}

    private Authed auth(ServerCore c) throws IOException {
        Raw cmd = raw(c.cmdPort());
        cmd.send(Packet.auth(c.sessions().currentCode()));
        Packet result = cmd.read();
        assertEquals(PacketType.AUTH_RESULT, result.type(), "AUTH_RESULT must be the first packet");
        Packet.AuthResultPayload payload = result.authResultPayload();
        assertEquals(Packet.AuthStatus.OK, payload.status());
        return new Authed(cmd, payload.token());
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

    private static ServerSession session(ServerCore c) {
        return c.sessions().current().orElseThrow();
    }

    /** Records when the close decision was taken and why, from a close hook. */
    private static final class CloseRecorder {
        final CountDownLatch decided = new CountDownLatch(1);
        volatile long decidedAtNanos;
        volatile CloseInfo info;

        CloseRecorder(ServerSession s) {
            s.lifecycle().addCloseHook(() -> {
                decidedAtNanos = System.nanoTime();
                info = s.lifecycle().closeInfo().orElse(null);
                decided.countDown();
            });
        }

        void await() throws InterruptedException {
            assertTrue(decided.await(WAIT_S, TimeUnit.SECONDS), "session was never closed");
        }
    }

    private void activate(ServerCore c, Authed a) throws IOException {
        a.cmd().send(Packet.screenInfo(1920, 1080));
        raw(c.imgPort()).send(Packet.attach(a.token()));
        a.cmd().readUntil(PacketType.SESSION_ACTIVE);
    }

    // ---- happy paths -----------------------------------------------------------------------

    @Test
    void authOkThenScreenInfoThenAttachActivates() throws Exception {
        ServerCore c = core(fast());
        Authed a = auth(c);
        assertEquals(Protocol.TOKEN_LEN, a.token().length);
        a.cmd().send(Packet.screenInfo(1920, 1080));
        raw(c.imgPort()).send(Packet.attach(a.token()));
        a.cmd().readUntil(PacketType.SESSION_ACTIVE);
        ServerSession s = session(c);
        assertEquals(ServerPhase.ACTIVE, s.phase());
        assertEquals(new Packet.SizePayload(1920, 1080), s.screen().orElseThrow());
    }

    @Test
    void attachBeforeScreenInfoAlsoActivates() throws Exception {
        ServerCore c = core(fast());
        Authed a = auth(c);
        raw(c.imgPort()).send(Packet.attach(a.token()));
        a.cmd().send(Packet.screenInfo(1280, 720));
        a.cmd().readUntil(PacketType.SESSION_ACTIVE);
        assertEquals(ServerPhase.ACTIVE, session(c).phase());
    }

    @Test
    void wrongCodeReturnsWrongCodeAndCloses() throws Exception {
        ServerCore c = core(fast());
        String code = c.sessions().currentCode();
        Raw cmd = raw(c.cmdPort());
        cmd.send(Packet.auth(code.equals("000000") ? "000001" : "000000"));
        Packet.AuthResultPayload result = cmd.read().authResultPayload();
        assertEquals(Packet.AuthStatus.WRONG_CODE, result.status());
        assertArrayEquals(new byte[Protocol.TOKEN_LEN], result.token());
        assertTrue(cmd.drain().isEmpty(), "closed right after the reply");
        assertTrue(c.sessions().current().isEmpty());
        assertEquals(code, c.sessions().currentCode());
    }

    @Test
    void secondClientGetsBusy() throws Exception {
        ServerCore c = core(fast());
        auth(c);
        Raw second = raw(c.cmdPort());
        second.send(Packet.auth(c.sessions().currentCode()));
        assertEquals(Packet.AuthStatus.BUSY, second.read().authResultPayload().status());
        assertTrue(second.drain().isEmpty());
    }

    // ---- handshake deadline ----------------------------------------------------------------

    @Test
    void silentConnectionClosedAtHandshakeDeadline() throws Exception {
        ServerCore c = core(fast());
        long start = System.nanoTime();
        Raw silent = raw(c.cmdPort());
        assertWindow(silent.awaitEndMs(start), 300, 300 + SLACK_MS, "silent connection closed");
    }

    @Test
    void dripFeedClosedAtHandshakeDeadline() throws Exception {
        ServerCore c = core(fast());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PacketIO.write(new DataOutputStream(bytes), Packet.auth(c.sessions().currentCode()));
        long start = System.nanoTime();
        Raw drip = raw(c.cmdPort());
        Thread feeder = new Thread(() -> {
            try {
                for (byte b : bytes.toByteArray()) {
                    drip.out.write(b);
                    drip.out.flush();
                    LockSupport.parkNanos(60_000_000L); // pacing only; the outcome is decided by the server
                }
            } catch (IOException ignored) {
                // server closed the connection
            }
        });
        feeder.setDaemon(true);
        feeder.start();
        assertWindow(drip.awaitEndMs(start), 300, 300 + SLACK_MS, "drip-fed connection closed");
        assertTrue(c.sessions().current().isEmpty(), "an incomplete AUTH never reserves");
    }

    @Test
    void poolFullClosesThirteenthImmediately() throws Exception {
        ServerCore c = core(fast());
        List<Raw> silent = new ArrayList<>();
        for (int i = 0; i < 12; i++) silent.add(raw(c.cmdPort())); // 4 running + 8 queued
        long start = System.nanoTime();
        Raw thirteenth = raw(c.cmdPort());
        assertTrue(thirteenth.awaitEndMs(start) < 200, "13th connection must be closed at once");
        for (Raw r : silent) r.awaitEndMs(System.nanoTime()); // all expire at their handshake deadline
        Authed a = auth(c); // the pool is usable again
        assertEquals(Protocol.TOKEN_LEN, a.token().length);
    }

    // ---- reserve deadline ------------------------------------------------------------------

    @Test
    void reservedWithoutAttachTimesOutAndCodeChanges() throws Exception {
        ServerCore c = core(fast());
        String oldCode = c.sessions().currentCode();
        Authed a = auth(c);
        ServerSession s = session(c);
        CloseRecorder rec = new CloseRecorder(s);
        rec.await();
        assertEquals("attach timeout", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - s.reservedAtNanos()), 500, 500 + SLACK_MS, "attach timeout decision");

        s.lifecycle().socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        long socketsMs = ms(System.nanoTime() - rec.decidedAtNanos);
        assertTrue(socketsMs <= GRACE.toMillis() + SLACK_MS, "sockets closed within grace + slack: " + socketsMs);
        List<Packet> tail = a.cmd().drain();
        Packet last = tail.get(tail.size() - 1);
        assertEquals(PacketType.DISCONNECT, last.type());
        assertEquals(Packet.DisconnectReason.TIMEOUT, last.disconnectPayload().reason());

        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        assertTrue(c.sessions().current().isEmpty());
        assertNotEquals(oldCode, c.sessions().currentCode());
    }

    // ---- image channel attach --------------------------------------------------------------

    @Test
    void attachWithBadVersionDoesNotConsumeToken() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        Raw bad = raw(c.imgPort());
        bad.send(Packet.attach(2, a.token()));
        bad.awaitEndMs(System.nanoTime());
        assertEquals(ServerPhase.RESERVED, session(c).phase());
        activate(c, a); // same token, right version
        assertEquals(ServerPhase.ACTIVE, session(c).phase());
    }

    @Test
    void attachAfterHandshakeDeadlineDoesNotConsumeToken() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        long start = System.nanoTime();
        Raw slow = raw(c.imgPort());
        assertWindow(slow.awaitEndMs(start), 300, 300 + SLACK_MS, "idle image socket closed");
        try {
            slow.send(Packet.attach(a.token())); // too late: the server already dropped this socket
        } catch (IOException expected) {
            // the closed connection may refuse the write
        }
        assertEquals(ServerPhase.RESERVED, session(c).phase());
        activate(c, a); // a fresh socket with the same token, in time
        assertEquals(ServerPhase.ACTIVE, session(c).phase());
    }

    @Test
    void wrongTokenAttachClosesOnlyThatSocket() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        byte[] other = a.token().clone();
        other[0] ^= 0x7F;
        Raw wrong = raw(c.imgPort());
        wrong.send(Packet.attach(other));
        wrong.awaitEndMs(System.nanoTime());
        assertFalse(session(c).lifecycle().isClosed());
        activate(c, a);
        assertEquals(ServerPhase.ACTIVE, session(c).phase());
    }

    @Test
    void garbageOnImagePortClosesOnlyThatSocket() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        Raw garbage = raw(c.imgPort());
        garbage.out.write(new byte[64]);
        garbage.out.write(0xFF);
        garbage.out.flush();
        garbage.awaitEndMs(System.nanoTime());
        assertFalse(session(c).lifecycle().isClosed());
        activate(c, a);
        assertEquals(ServerPhase.ACTIVE, session(c).phase());
    }

    // ---- close paths -----------------------------------------------------------------------

    @Test
    void disconnectPacketClosesWithHooksOutsideLock() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        activate(c, a);
        ServerSession s = session(c);
        AtomicBoolean lockFreeInHook = new AtomicBoolean();
        CountDownLatch hookRan = new CountDownLatch(1);
        s.lifecycle().addCloseHook(() -> {
            // another thread needs the session lock: it can only finish if this hook does not hold it
            Thread probe = new Thread(() -> s.lifecycle().phase(), "test-lock-probe");
            probe.setDaemon(true);
            probe.start();
            try {
                probe.join(WAIT_S * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lockFreeInHook.set(!probe.isAlive());
            hookRan.countDown();
        });
        a.cmd().send(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
        assertTrue(hookRan.await(WAIT_S, TimeUnit.SECONDS));
        assertTrue(lockFreeInHook.get(), "close hooks must run after the session lock is released");
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.USER, s.lifecycle().closeInfo().orElseThrow().reason());
    }

    @Test
    void portInUseFailsStartAndReleasesOtherPort() throws Exception {
        int freeCmdPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freeCmdPort = probe.getLocalPort();
        }
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerCore c = new ServerCore(config(fast(), freeCmdPort, occupied.getLocalPort()));
            cores.add(c);
            assertThrows(IOException.class, c::start);
        }
        try (ServerSocket again = new ServerSocket(freeCmdPort, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(freeCmdPort, again.getLocalPort(), "the command port must be released after a failed start");
        }
    }

    @Test
    void noTrafficClosesAfterLiveness() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        a.cmd().send(Packet.screenInfo(1920, 1080));
        raw(c.imgPort()).send(Packet.attach(a.token()));
        a.cmd().readUntil(PacketType.SESSION_ACTIVE);
        ServerSession s = session(c);
        CloseRecorder rec = new CloseRecorder(s);
        long lastPacketSentAt = System.nanoTime(); // SCREEN_INFO was the last packet the server received
        // the client now stays silent and never answers PING
        rec.await();
        assertEquals("liveness timeout", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - lastPacketSentAt), 800 - 100, 1400, "liveness decision");
        s.lifecycle().socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(ms(System.nanoTime() - rec.decidedAtNanos) <= GRACE.toMillis() + SLACK_MS);
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
    }

    @Test
    void terminatedLeavesNoSessionThreads() throws Exception {
        Set<Thread> before = sessionThreads();
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        Authed a = auth(c);
        activate(c, a);
        ServerSession s = session(c);
        awaitTrue(() -> !newThreads(before).isEmpty(), "session threads to start");
        a.cmd().send(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        awaitTrue(() -> newThreads(before).isEmpty(), "session threads to exit");
        assertEquals(0, s.lifecycle().liveWorkers());
    }

    private static Set<Thread> sessionThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && (t.getName().equals("rd-cmd-reader")
                        || t.getName().startsWith("rd-cmd-writer-")))
                .collect(Collectors.toSet());
    }

    private static Set<Thread> newThreads(Set<Thread> before) {
        Set<Thread> now = sessionThreads();
        now.removeAll(before);
        return now;
    }

    @Test
    void handOverRacingShutdown() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerCore.Seams seams = new ServerCore.Seams(null, () -> {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // test teardown
            }
        });
        ServerCore c = core(timeouts(Duration.ofSeconds(5)), seams);
        Authed a = auth(c);
        ServerSession s = session(c);
        Raw img = raw(c.imgPort());
        img.send(Packet.attach(a.token()));
        assertTrue(reached.await(WAIT_S, TimeUnit.SECONDS), "attach reached the point after the token check");

        Thread closer = new Thread(c::close, "test-closer");
        closer.setDaemon(true);
        closer.start();
        awaitTrue(() -> c.sessions().shuttingDown(), "shutdown to begin");
        release.countDown(); // the hand-over now races the shutdown that is already under way
        closer.join(WAIT_S * 1000L);
        assertFalse(closer.isAlive(), "close() must finish");

        assertTrue(s.lifecycle().isClosed());
        img.awaitEndMs(System.nanoTime()); // closed whether attach was rejected or handed over
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
    }

    @Test
    void readerStartFailureClosesSession() throws Exception {
        ServerCore.Seams seams = new ServerCore.Seams(r -> new Thread(r) {
            @Override
            public synchronized void start() {
                if (getName().equals("rd-cmd-reader")) throw new IllegalStateException("cannot start reader");
                super.start();
            }
        }, null);
        ServerCore c = core(fast(), seams);
        String oldCode = c.sessions().currentCode();
        Authed a = auth(c);
        ServerSession s = session(c);
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        CloseInfo info = s.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("cannot start worker", info.message());
        assertTrue(c.sessions().current().isEmpty(), "the slot is freed");
        assertNotEquals(oldCode, c.sessions().currentCode());
        a.cmd().drain();
    }

    @Test
    void tickerRegistrationsReturnToBaseline() throws Exception {
        ServerCore c = core(timeouts(Duration.ofSeconds(5)));
        int baseline = c.tickerRegistrations();
        Authed a = auth(c);
        activate(c, a);
        ServerSession s = session(c);
        awaitTrue(() -> c.tickerRegistrations() == baseline + 3, "session, heartbeat and watchdog registrations");
        a.cmd().send(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
        s.lifecycle().terminated().get(5, TimeUnit.SECONDS);
        assertEquals(baseline, c.tickerRegistrations());
    }
}
