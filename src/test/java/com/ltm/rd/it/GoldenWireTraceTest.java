package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.viewer.ViewerConfig;
import com.ltm.rd.viewer.ViewerHooks;
import com.ltm.rd.viewer.ViewerPhase;
import com.ltm.rd.viewer.ViewerSession;
import com.ltm.rd.viewer.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.it.RecordingRelay.Dir;
import com.ltm.rd.host.HostConfig;
import com.ltm.rd.host.HostCore;
import com.ltm.rd.host.HostPhase;
import com.ltm.rd.host.HostSession;
import com.ltm.rd.host.HostTestAccess;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Golden wire traces of the v1 handshake and its failure flows, recorded per channel between a real
 * {@link HostCore} and a real {@link ViewerSession} through a {@link RecordingRelay}. The expected
 * traces below are literals on purpose: a refactor that must not change behaviour has to leave every
 * one of them byte for byte identical, and a change of the wire shows up as a diff of these lines.
 *
 * <p>Line format: {@code <direction> <packet> @<phase of the sender>}; see {@link RecordingRelay}.
 * {@code C>S} is Client to Server, {@code S>C} Server to Client. Consecutive FRAMEs collapse into one
 * line. The main traces run on fake clocks that only the reserve-expiry case advances, with a ping
 * interval far longer than any case, so no PING/PONG appears. The one case that lets the real clock
 * run ({@link #attachSlowerThanPingIntervalOnlyAddsHeartbeats()}) must reduce to the success trace.
 *
 * <p>Nothing here sleeps: every wait is a latch, a future or a condition with a bound.
 */
@Timeout(120)
class GoldenWireTraceTest {
    private static final int WAIT_S = 10;
    private static final Duration HOUR = Duration.ofHours(1);

    // ---- the golden traces ----------------------------------------------------------------

    /** Handshake up to ACTIVE, command channel. */
    private static final List<String> SUCCESS_CMD = List.of(
            "C>S AUTH(v2) @AUTHENTICATING",
            "S>C AUTH_RESULT(OK) @RESERVED",
            "S>C SCREEN_INFO(1920x1080) @RESERVED",
            "S>C SESSION_ACTIVE @ACTIVE");

    /** Handshake up to ACTIVE, image channel: ATTACH, then frames. */
    private static final List<String> SUCCESS_IMG = List.of(
            "C>S ATTACH(v2) @ATTACHING",
            "S>C FRAME @ACTIVE");

    private static final List<String> WRONG_CODE_CMD = List.of(
            "C>S AUTH(v2) @AUTHENTICATING",
            "S>C AUTH_RESULT(WRONG_CODE) @-");

    private static final List<String> LOCKED_CMD = List.of(
            "C>S AUTH(v2) @AUTHENTICATING",
            "S>C AUTH_RESULT(LOCKED) @-");

    private static final List<String> BUSY_CMD = List.of(
            "C>S AUTH(v2) @AUTHENTICATING",
            "S>C AUTH_RESULT(BUSY) @-");

    private static final List<String> NO_PACKETS = List.of();

    // ---- fixtures -------------------------------------------------------------------------

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
    private final FakeClock serverClock = new FakeClock();
    private final FakeClock clientClock = new FakeClock();
    private final Ticker clientTicker = new Ticker(Duration.ofMillis(20), clientClock);
    private final FakeInputDevice device = new FakeInputDevice();
    private final InputExecutor executor = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 256,
            InputExecutor.defaultThreadFactory(), t -> {});
    private final AtomicLong generations = new AtomicLong();
    private final List<ViewerSession> clients = new ArrayList<>();
    private final List<RecordingRelay> relays = new ArrayList<>();
    private final List<HostCore> cores = new ArrayList<>();
    private final List<Ticker> extraTickers = new ArrayList<>();


    private final com.ltm.rd.host.HostTestAccess.HostResources hostResources = new com.ltm.rd.host.HostTestAccess.HostResources(executor);

    @AfterEach
    void cleanup() throws Exception {
        for (ViewerSession c : clients) c.cancel();
        for (RecordingRelay r : relays) r.close();
        for (HostCore c : cores) c.close();
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        clientTicker.close();
        for (Ticker t : extraTickers) t.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        hostResources.close();
    }

    /**
     * Deadlines that cannot fire by accident: the clocks are fake and frozen, and the real socket
     * timeouts are far beyond any case. Only {@code reserve} (5 s of fake time) is meant to expire.
     */
    private static Timeouts frozen() {
        return timeouts(HOUR, Duration.ofSeconds(5));
    }

    private static Timeouts timeouts(Duration pingInterval, Duration reserve) {
        return new Timeouts(Duration.ofSeconds(30), reserve, Duration.ofSeconds(30), Duration.ofSeconds(30),
                pingInterval, HOUR.multipliedBy(2), Duration.ofSeconds(60), HOUR, HOUR.multipliedBy(2),
                Duration.ofSeconds(60), HOUR, HOUR, Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ZERO,
                Duration.ofSeconds(30), Duration.ofMillis(20));
    }

    private HostCore core(Timeouts t, LongSupplier clock) throws IOException {
        HostCore c = hostResources.core(new HostConfig(InetAddress.getLoopbackAddress(), 0, 0, t, clock,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    private RecordingRelay relay(HostCore core, boolean holdImage, boolean tamperAttachToken) throws IOException {
        RecordingRelay r = new RecordingRelay(core, holdImage, tamperAttachToken);
        relays.add(r);
        return r;
    }

    private ViewerSession client(RecordingRelay relay, String code, Timeouts t, LongSupplier clock, Ticker ticker) {
        ViewerConfig cfg = new ViewerConfig("127.0.0.1", relay.cmdPort(), relay.imgPort(), code, t, clock);
        ViewerSession c = new ViewerSession(cfg, generations.incrementAndGet(), SocketConnector.DEFAULT, ViewerHooks.none(), ticker, scheduler, cleanup);
        relay.bind(c);
        clients.add(c);
        return c;
    }

    /** A client on the frozen fake clock. */
    private ViewerSession client(RecordingRelay relay, String code) {
        return client(relay, code, frozen(), clientClock, clientTicker);
    }

    private static String wrongCode(HostCore c) {
        String code = c.sessions().currentCode();
        return code.equals("000000") ? "000001" : "000000";
    }

    private static void awaitTerminated(ViewerSession c) throws Exception {
        c.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, c.lifecycle().liveWorkers());
    }

    private static void awaitSeen(RecordingRelay.Trace trace, Dir dir, PacketType type) throws InterruptedException {
        assertTrue(trace.await(dir, type), "timed out waiting for " + dir + " " + type);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static HostSession serverSession(HostCore core) {
        return core.sessions().current().orElseThrow();
    }

    /** Runs a client to ACTIVE and until the first FRAME crossed the relay. */
    private ViewerSession activeClient(HostCore core, RecordingRelay relay) throws Exception {
        ViewerSession c = client(relay, core.sessions().currentCode());
        assertTrue(c.start());
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.SCREEN_INFO);
        relay.openImageGate();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        awaitSeen(relay.img(), Dir.S2C, PacketType.FRAME);
        return c;
    }

    // ---- success -----------------------------------------------------------------------------

    @Test
    void successFlowTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        RecordingRelay relay = relay(core, true, false);
        ViewerSession c = activeClient(core, relay);
        relay.freeze();

        assertEquals(SUCCESS_CMD, relay.cmd().lines());
        assertEquals(SUCCESS_IMG, relay.img().lines());
        assertEquals(ViewerPhase.ACTIVE, c.phase());
        assertEquals(HostPhase.ACTIVE, serverSession(core).phase());
        assertEquals(new Packet.SizePayload(1920, 1080), serverSession(core).screen().orElseThrow());
        assertEquals(1, core.sessions().current().orElseThrow().inputBeginCount());
    }

    // ---- failure flows -------------------------------------------------------------------------

    @Test
    void wrongCodeTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, true, false);
        ViewerSession c = client(relay, wrongCode(core));
        c.start();
        awaitTerminated(c);
        relay.freeze();

        assertEquals(WRONG_CODE_CMD, relay.cmd().lines());
        assertEquals(NO_PACKETS, relay.img().lines());
        assertEquals(Packet.AuthStatus.WRONG_CODE, c.authFailure().orElseThrow());
        CloseInfo info = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("auth rejected: WRONG_CODE", info.message());
        assertTrue(core.sessions().current().isEmpty(), "a wrong code never reserves");
        assertEquals(codeBefore, core.sessions().currentCode(), "a wrong code does not change the code");
    }

    @Test
    void ipLockTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        for (int attempt = 1; attempt <= 3; attempt++) {
            RecordingRelay relay = relay(core, true, false);
            ViewerSession c = client(relay, wrongCode(core));
            c.start();
            awaitTerminated(c);
            relay.freeze();
            assertEquals(WRONG_CODE_CMD, relay.cmd().lines(), "attempt " + attempt);
            assertEquals(Packet.AuthStatus.WRONG_CODE, c.authFailure().orElseThrow());
        }

        RecordingRelay relay = relay(core, true, false);
        ViewerSession locked = client(relay, codeBefore); // even the right code is refused now
        locked.start();
        awaitTerminated(locked);
        relay.freeze();

        assertEquals(LOCKED_CMD, relay.cmd().lines());
        assertEquals(NO_PACKETS, relay.img().lines());
        assertEquals(Packet.AuthStatus.LOCKED, locked.authFailure().orElseThrow());
        assertEquals("auth rejected: LOCKED", locked.lifecycle().closeInfo().orElseThrow().message());
        assertTrue(core.sessions().current().isEmpty());
        assertEquals(codeBefore, core.sessions().currentCode());
    }

    @Test
    void busyTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        RecordingRelay first = relay(core, true, false);
        ViewerSession active = activeClient(core, first);
        HostSession session = serverSession(core);
        String codeInUse = core.sessions().currentCode();

        RecordingRelay second = relay(core, true, false);
        ViewerSession busy = client(second, codeInUse);
        busy.start();
        awaitTerminated(busy);
        first.freeze();
        second.freeze();

        assertEquals(BUSY_CMD, second.cmd().lines());
        assertEquals(NO_PACKETS, second.img().lines());
        assertEquals(Packet.AuthStatus.BUSY, busy.authFailure().orElseThrow());
        // the running session is untouched: same object, same phase, same code, same traces
        assertSame(session, core.sessions().current().orElseThrow());
        assertEquals(HostPhase.ACTIVE, session.phase());
        assertEquals(codeInUse, core.sessions().currentCode());
        assertEquals(ViewerPhase.ACTIVE, active.phase());
        assertEquals(SUCCESS_CMD, first.cmd().lines());
        assertEquals(SUCCESS_IMG, first.img().lines());
    }

    @Test
    void wrongTokenTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        RecordingRelay relay = relay(core, false, true);
        ViewerSession c = client(relay, core.sessions().currentCode());
        c.start();
        assertTrue(relay.awaitImageClosedByServer(), "the server must drop the image connection");
        relay.freeze();

        assertEquals(List.of(
                "C>S AUTH(v2) @AUTHENTICATING",
                "S>C AUTH_RESULT(OK) @RESERVED",
                "S>C SCREEN_INFO(1920x1080) @RESERVED"), relay.cmd().lines());
        assertEquals(List.of("C>S ATTACH(v2) @ATTACHING"), relay.img().lines());
        // only that one connection ended: the session still waits for a valid ATTACH
        HostSession session = serverSession(core);
        assertFalse(HostTestAccess.lifecycle(session).isClosed());
        assertEquals(HostPhase.RESERVED, session.phase());
        assertEquals(ViewerPhase.ATTACHING, c.phase());
    }

    @Test
    void reserveExpiredTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, true, false); // the image connection never reaches the server
        ViewerSession c = client(relay, codeBefore);
        c.start();
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.SCREEN_INFO);
        HostSession session = serverSession(core);
        assertEquals(HostPhase.RESERVED, session.phase());

        serverClock.advance(Duration.ofSeconds(6)); // past reserve (5 s); the server's ticker decides
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.DISCONNECT);
        relay.freeze();

        assertEquals(List.of(
                "C>S AUTH(v2) @AUTHENTICATING",
                "S>C AUTH_RESULT(OK) @RESERVED",
                "S>C SCREEN_INFO(1920x1080) @RESERVED",
                "S>C DISCONNECT(TIMEOUT, \"attach timeout\") @CLOSING"), relay.cmd().lines());
        assertEquals(NO_PACKETS, relay.img().lines());
        CloseInfo serverInfo = session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.TIMEOUT, serverInfo.reason());
        assertEquals("attach timeout", serverInfo.message());
        awaitTerminated(c);
        CloseInfo clientInfo = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.TIMEOUT, clientInfo.reason());
        assertEquals("peer disconnected", clientInfo.message());
        assertEquals(0, session.inputBeginCount());
        awaitTrue(() -> core.sessions().current().isEmpty(), "the slot is free again");
        assertNotEquals(codeBefore, core.sessions().currentCode(), "the code changed with the slot");
    }

    @Test
    void clientDisconnectDuringActiveTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, true, false);
        ViewerSession c = activeClient(core, relay);
        HostSession session = serverSession(core);

        c.cancel();
        awaitSeen(relay.cmd(), Dir.C2S, PacketType.DISCONNECT);
        relay.freeze();

        List<String> expectedCmd = new ArrayList<>(SUCCESS_CMD);
        expectedCmd.add("C>S DISCONNECT(USER, \"cancelled\") @CLOSING");
        assertEquals(expectedCmd, relay.cmd().lines());
        assertEquals(SUCCESS_IMG, relay.img().lines());
        CloseInfo serverInfo = session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.USER, serverInfo.reason());
        assertEquals("peer disconnected", serverInfo.message());
        awaitTerminated(c);
        CloseInfo clientInfo = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.USER, clientInfo.reason());
        assertEquals("cancelled", clientInfo.message());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the server slot to be freed");
        assertNotEquals(codeBefore, core.sessions().currentCode());
    }

    @Test
    void serverDisconnectDuringActiveTrace() throws Exception {
        HostCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, true, false);
        ViewerSession c = activeClient(core, relay);
        HostSession session = serverSession(core);

        session.disconnect();
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.DISCONNECT);
        relay.freeze();

        List<String> expectedCmd = new ArrayList<>(SUCCESS_CMD);
        expectedCmd.add("S>C DISCONNECT(USER, \"disconnected\") @CLOSING");
        assertEquals(expectedCmd, relay.cmd().lines());
        assertEquals(SUCCESS_IMG, relay.img().lines());
        CloseInfo serverInfo = session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.USER, serverInfo.reason());
        assertEquals("disconnected", serverInfo.message());
        awaitTerminated(c);
        CloseInfo clientInfo = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.USER, clientInfo.reason());
        assertEquals("peer disconnected", clientInfo.message());
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the server slot to be freed");
        assertNotEquals(codeBefore, core.sessions().currentCode());
    }

    // ---- heartbeat ---------------------------------------------------------------------------

    /**
     * The only variant where PING/PONG may appear: the image connection is held until the server's
     * first PING crossed the relay, so the ping interval (100 ms, real clock) elapses before ATTACH.
     * The heartbeat may show up anywhere after the hand-over, also before SESSION_ACTIVE, but never
     * before AUTH_RESULT, and without it the trace is the success trace.
     */
    @Test
    void prepareSlowerThanPingIntervalOnlyAddsHeartbeats() throws Exception {
        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        hostResources.factory = new com.ltm.rd.host.AdapterFactory() {
            @Override public com.ltm.rd.host.InputDevice inputDevice() { throw new AssertionError(); }
            @Override public void refreshInputDevice() {
                preparing.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            @Override public com.ltm.rd.host.ScreenSource newScreenSource() { return new FakeScreenSource(1920, 1080); }
        };
        try {
        Timeouts slowAttach = timeouts(Duration.ofMillis(100), Duration.ofSeconds(30));
        HostCore core = core(slowAttach, System::nanoTime);
        RecordingRelay relay = relay(core, true, false);
        Ticker realTicker = new Ticker(Duration.ofMillis(20), System::nanoTime);
        extraTickers.add(realTicker);
        ViewerSession c = client(relay, core.sessions().currentCode(), slowAttach, System::nanoTime, realTicker);
        c.start();

        awaitSeen(relay.cmd(), Dir.S2C, PacketType.PING); // the server pinged while still waiting for ATTACH
        assertEquals(ViewerPhase.ATTACHING, c.phase(), "no ATTACH yet: the image connection is held");
        assertTrue(preparing.await(WAIT_S, TimeUnit.SECONDS));
        assertEquals(-1, relay.cmd().indexOf(Dir.S2C, PacketType.SCREEN_INFO));
        release.countDown();
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.SCREEN_INFO);
        relay.openImageGate();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        awaitSeen(relay.img(), Dir.S2C, PacketType.FRAME);
        relay.freeze();

        RecordingRelay.Trace cmd = relay.cmd();
        int authResult = cmd.indexOf(Dir.S2C, PacketType.AUTH_RESULT);
        int firstHeartbeat = cmd.indexOfHeartbeat();
        int sessionActive = cmd.indexOf(Dir.S2C, PacketType.SESSION_ACTIVE);
        assertTrue(authResult >= 0 && firstHeartbeat > authResult,
                "no PING/PONG before AUTH_RESULT: " + cmd.lines());
        assertTrue(firstHeartbeat < sessionActive, "the scenario needs a PING before SESSION_ACTIVE: " + cmd.lines());
        assertEquals(SUCCESS_CMD, cmd.linesWithoutHeartbeat());
        assertEquals(SUCCESS_IMG, relay.img().linesWithoutHeartbeat());
        assertEquals(ViewerPhase.ACTIVE, c.phase());
        } finally { release.countDown(); hostResources.factory = null; }
    }
}
