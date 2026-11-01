package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientPhase;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.it.RecordingRelay.Dir;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerPhase;
import com.ltm.rd.server.ServerSession;
import com.ltm.rd.server.ServerTestAccess;
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
 * {@link ServerCore} and a real {@link ClientSession} through a {@link RecordingRelay}. The expected
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
            "C>S AUTH(v1) @AUTHENTICATING",
            "S>C AUTH_RESULT(OK) @RESERVED",
            "C>S SCREEN_INFO(1920x1080) @ATTACHING",
            "S>C SESSION_ACTIVE @ACTIVE");

    /** Handshake up to ACTIVE, image channel: ATTACH, then frames. */
    private static final List<String> SUCCESS_IMG = List.of(
            "C>S ATTACH(v1) @ATTACHING",
            "C>S FRAME @ACTIVE");

    private static final List<String> WRONG_CODE_CMD = List.of(
            "C>S AUTH(v1) @AUTHENTICATING",
            "S>C AUTH_RESULT(WRONG_CODE) @-");

    private static final List<String> LOCKED_CMD = List.of(
            "C>S AUTH(v1) @AUTHENTICATING",
            "S>C AUTH_RESULT(LOCKED) @-");

    private static final List<String> BUSY_CMD = List.of(
            "C>S AUTH(v1) @AUTHENTICATING",
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
    private final List<ClientSession> clients = new ArrayList<>();
    private final List<RecordingRelay> relays = new ArrayList<>();
    private final List<ServerCore> cores = new ArrayList<>();
    private final List<Ticker> extraTickers = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (ClientSession c : clients) c.cancel();
        for (RecordingRelay r : relays) r.close();
        for (ServerCore c : cores) c.close();
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        clientTicker.close();
        for (Ticker t : extraTickers) t.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
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

    private ServerCore core(Timeouts t, LongSupplier clock) throws IOException {
        ServerCore c = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, t, clock,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    private RecordingRelay relay(ServerCore core, boolean holdImage, boolean tamperAttachToken) throws IOException {
        RecordingRelay r = new RecordingRelay(core, holdImage, tamperAttachToken);
        relays.add(r);
        return r;
    }

    private ClientSession client(RecordingRelay relay, String code, Timeouts t, LongSupplier clock, Ticker ticker) {
        ClientConfig cfg = new ClientConfig("127.0.0.1", relay.cmdPort(), relay.imgPort(), code, t, clock);
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), executor,
                new FakeScreenSource(1920, 1080), SocketConnector.DEFAULT, ClientHooks.none(), ticker, scheduler,
                cleanup);
        relay.bind(c);
        clients.add(c);
        return c;
    }

    /** A client on the frozen fake clock. */
    private ClientSession client(RecordingRelay relay, String code) {
        return client(relay, code, frozen(), clientClock, clientTicker);
    }

    private static String wrongCode(ServerCore c) {
        String code = c.sessions().currentCode();
        return code.equals("000000") ? "000001" : "000000";
    }

    private static void awaitTerminated(ClientSession c) throws Exception {
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

    private static ServerSession serverSession(ServerCore core) {
        return core.sessions().current().orElseThrow();
    }

    /** Runs a client to ACTIVE and until the first FRAME crossed the relay. */
    private ClientSession activeClient(ServerCore core, RecordingRelay relay) throws Exception {
        ClientSession c = client(relay, core.sessions().currentCode());
        assertTrue(c.start());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        awaitSeen(relay.img(), Dir.C2S, PacketType.FRAME);
        return c;
    }

    // ---- success -----------------------------------------------------------------------------

    @Test
    void successFlowTrace() throws Exception {
        ServerCore core = core(frozen(), serverClock);
        RecordingRelay relay = relay(core, false, false);
        ClientSession c = activeClient(core, relay);
        relay.freeze();

        assertEquals(SUCCESS_CMD, relay.cmd().lines());
        assertEquals(SUCCESS_IMG, relay.img().lines());
        assertEquals(ClientPhase.ACTIVE, c.phase());
        assertEquals(ServerPhase.ACTIVE, serverSession(core).phase());
        assertEquals(new Packet.SizePayload(1920, 1080), serverSession(core).screen().orElseThrow());
        assertEquals(1, c.inputBeginCount());
    }

    // ---- failure flows -------------------------------------------------------------------------

    @Test
    void wrongCodeTrace() throws Exception {
        ServerCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, false, false);
        ClientSession c = client(relay, wrongCode(core));
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
        ServerCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        for (int attempt = 1; attempt <= 3; attempt++) {
            RecordingRelay relay = relay(core, false, false);
            ClientSession c = client(relay, wrongCode(core));
            c.start();
            awaitTerminated(c);
            relay.freeze();
            assertEquals(WRONG_CODE_CMD, relay.cmd().lines(), "attempt " + attempt);
            assertEquals(Packet.AuthStatus.WRONG_CODE, c.authFailure().orElseThrow());
        }

        RecordingRelay relay = relay(core, false, false);
        ClientSession locked = client(relay, codeBefore); // even the right code is refused now
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
        ServerCore core = core(frozen(), serverClock);
        RecordingRelay first = relay(core, false, false);
        ClientSession active = activeClient(core, first);
        ServerSession session = serverSession(core);
        String codeInUse = core.sessions().currentCode();

        RecordingRelay second = relay(core, false, false);
        ClientSession busy = client(second, codeInUse);
        busy.start();
        awaitTerminated(busy);
        first.freeze();
        second.freeze();

        assertEquals(BUSY_CMD, second.cmd().lines());
        assertEquals(NO_PACKETS, second.img().lines());
        assertEquals(Packet.AuthStatus.BUSY, busy.authFailure().orElseThrow());
        // the running session is untouched: same object, same phase, same code, same traces
        assertSame(session, core.sessions().current().orElseThrow());
        assertEquals(ServerPhase.ACTIVE, session.phase());
        assertEquals(codeInUse, core.sessions().currentCode());
        assertEquals(ClientPhase.ACTIVE, active.phase());
        assertEquals(SUCCESS_CMD, first.cmd().lines());
        assertEquals(SUCCESS_IMG, first.img().lines());
    }

    @Test
    void wrongTokenTrace() throws Exception {
        ServerCore core = core(frozen(), serverClock);
        RecordingRelay relay = relay(core, false, true);
        ClientSession c = client(relay, core.sessions().currentCode());
        c.start();
        assertTrue(relay.awaitImageClosedByServer(), "the server must drop the image connection");
        relay.freeze();

        assertEquals(List.of(
                "C>S AUTH(v1) @AUTHENTICATING",
                "S>C AUTH_RESULT(OK) @RESERVED",
                "C>S SCREEN_INFO(1920x1080) @ATTACHING"), relay.cmd().lines());
        assertEquals(List.of("C>S ATTACH(v1) @ATTACHING"), relay.img().lines());
        // only that one connection ended: the session still waits for a valid ATTACH
        ServerSession session = serverSession(core);
        assertFalse(ServerTestAccess.lifecycle(session).isClosed());
        assertEquals(ServerPhase.RESERVED, session.phase());
        assertEquals(ClientPhase.ATTACHING, c.phase());
    }

    @Test
    void reserveExpiredTrace() throws Exception {
        ServerCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, true, false); // the image connection never reaches the server
        ClientSession c = client(relay, codeBefore);
        c.start();
        awaitSeen(relay.cmd(), Dir.C2S, PacketType.SCREEN_INFO);
        ServerSession session = serverSession(core);
        assertEquals(ServerPhase.RESERVED, session.phase());

        serverClock.advance(Duration.ofSeconds(6)); // past reserve (5 s); the server's ticker decides
        awaitSeen(relay.cmd(), Dir.S2C, PacketType.DISCONNECT);
        relay.freeze();

        assertEquals(List.of(
                "C>S AUTH(v1) @AUTHENTICATING",
                "S>C AUTH_RESULT(OK) @RESERVED",
                "C>S SCREEN_INFO(1920x1080) @ATTACHING",
                "S>C DISCONNECT(TIMEOUT, \"attach timeout\") @CLOSING"), relay.cmd().lines());
        assertEquals(NO_PACKETS, relay.img().lines());
        CloseInfo serverInfo = session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.TIMEOUT, serverInfo.reason());
        assertEquals("attach timeout", serverInfo.message());
        awaitTerminated(c);
        CloseInfo clientInfo = c.lifecycle().closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.TIMEOUT, clientInfo.reason());
        assertEquals("peer disconnected", clientInfo.message());
        assertEquals(0, c.inputBeginCount());
        awaitTrue(() -> core.sessions().current().isEmpty(), "the slot is free again");
        assertNotEquals(codeBefore, core.sessions().currentCode(), "the code changed with the slot");
    }

    @Test
    void clientDisconnectDuringActiveTrace() throws Exception {
        ServerCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, false, false);
        ClientSession c = activeClient(core, relay);
        ServerSession session = serverSession(core);

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
        ServerCore core = core(frozen(), serverClock);
        String codeBefore = core.sessions().currentCode();
        RecordingRelay relay = relay(core, false, false);
        ClientSession c = activeClient(core, relay);
        ServerSession session = serverSession(core);

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
    void attachSlowerThanPingIntervalOnlyAddsHeartbeats() throws Exception {
        Timeouts slowAttach = timeouts(Duration.ofMillis(100), Duration.ofSeconds(30));
        ServerCore core = core(slowAttach, System::nanoTime);
        RecordingRelay relay = relay(core, true, false);
        Ticker realTicker = new Ticker(Duration.ofMillis(20), System::nanoTime);
        extraTickers.add(realTicker);
        ClientSession c = client(relay, core.sessions().currentCode(), slowAttach, System::nanoTime, realTicker);
        c.start();

        awaitSeen(relay.cmd(), Dir.S2C, PacketType.PING); // the server pinged while still waiting for ATTACH
        assertEquals(ClientPhase.ATTACHING, c.phase(), "no ATTACH yet: the image connection is held");
        relay.openImageGate();
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        awaitSeen(relay.img(), Dir.C2S, PacketType.FRAME);
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
        assertEquals(ClientPhase.ACTIVE, c.phase());
    }
}
