package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.Packet;
import com.ltm.rd.host.AdapterException;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerSession;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The Client role of the runtime: a real ServerCore on loopback, fake adapters, seams for blocking. */
@Timeout(120)
class AppRuntimeClientTest {
    private final List<AppRuntime> runtimes = new ArrayList<>();
    private final List<ServerCore> cores = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<FakeAdapterFactory.Gate> adapterGates = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-pool");
        t.setDaemon(true);
        return t;
    });

    private Set<Thread> baseline;

    @BeforeEach
    void captureThreads() {
        baseline = liveRdThreads();
    }


    private final com.ltm.rd.server.ServerTestAccess.HostResources hostResources = new com.ltm.rd.server.ServerTestAccess.HostResources();

    @AfterEach
    void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        adapterGates.forEach(FakeAdapterFactory.Gate::open);
        for (AutoCloseable r : resources) r.close();
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
        for (ServerCore c : cores) c.close();
        pool.shutdownNow();
        awaitNoNewRdThreads(baseline); // nothing this test started may outlive it
        hostResources.close();
    }

    // ---- fixtures --------------------------------------------------------------------------

    private ServerCore core() throws Exception {
        ServerCore c = hostResources.core(new ServerConfig(LO, 0, 0, TestTimeouts.relaxed(), System::nanoTime,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    private AppRuntime client(ServerCore core, FakeAdapterFactory adapters, AppRuntime.Seams seams) throws Exception {
        hostResources.factory = adapters;
        return client(core.cmdPort(), core.imgPort(), adapters, seams, System::nanoTime);
    }

    private AppRuntime client(int cmdPort, int imgPort, FakeAdapterFactory adapters, AppRuntime.Seams seams,
            java.util.function.LongSupplier clock) throws Exception {
        AppRuntime rt = AppRuntime.forViewer(clientConfig(cmdPort, imgPort, clock), seams);
        runtimes.add(rt);
        return rt;
    }

    private static AppRuntime.Seams seams(SocketConnector connector, ClientHooks hooks) {
        return new AppRuntime.Seams(hooks == null ? ClientHooks.none() : hooks, connector == null ? SocketConnector.DEFAULT : connector, UnaryOperator.identity(), () -> { }, () -> { }, () -> { });
    }

    private static String code(ServerCore core) {
        return core.sessions().currentCode();
    }

    private ClientSession session(AppRuntime rt) {
        awaitTrue("a client session", () -> rt.clientSession().isPresent());
        return rt.clientSession().orElseThrow();
    }

    /** connect() to the core, wait for ACTIVE and return the generation. */
    private long connectActive(AppRuntime rt, ServerCore core) {
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        return await(rt, s -> s.phase() == UiPhase.ACTIVE).generation();
    }

    /** disconnect() and wait until the session is terminated, input is released and the server slot is free. */
    private void disconnectAndSettle(AppRuntime rt, ServerCore core) throws Exception {
        ClientSession cs = session(rt);
        assertTrue(rt.disconnect());
        get(cs.terminated());
        get(cs.inputReleased());
        awaitTrue("server slot", () -> core.sessions().current().isEmpty());
        await(rt, s -> s.phase() == UiPhase.IDLE);
        assertSessionResourcesReleased(rt);
    }

    /** After a session ended: no ticker registration and no session thread is left behind. */
    private void assertSessionResourcesReleased(AppRuntime rt) {
        awaitTrue("ticker registrations back to 0", () -> rt.tickerRegistrations() == 0);
        awaitNoSessionThreads(baseline);
    }

    /** A clock that can be moved forward without waiting. */
    private static final class OffsetClock implements java.util.function.LongSupplier {
        private final AtomicLong offset = new AtomicLong();

        @Override
        public long getAsLong() {
            return System.nanoTime() + offset.get();
        }

        void advance(Duration d) {
            offset.addAndGet(d.toNanos());
        }
    }

    /** Parks every connect until {@link #release}, then connects for real. */
    private static final class BlockingConnector implements SocketConnector {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void connect(Socket socket, InetSocketAddress address, int timeoutMs) throws java.io.IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            socket.connect(address, timeoutMs);
        }
    }

    // ---- creation and input validation -------------------------------------------------------



    @Test
    void connectInvalidInputIsRejectedWithoutSideEffects() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt = client(1, 2, adapters, AppRuntime.Seams.none(), System::nanoTime);
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect(" ", "123456"));
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect("127.0.0.1", "12345"));
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect("127.0.0.1", "1234567"));
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect("127.0.0.1", "12345a"));
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect(null, "123456"));
        assertEquals(ConnectOutcome.INVALID_INPUT, rt.connect("127.0.0.1", null));
        assertEquals(0, adapters.newScreenCalls());
        assertEquals(0, adapters.refreshCalls());
        Snapshot s = rt.snapshot();
        assertEquals(0, s.generation());
        assertEquals(UiPhase.IDLE, s.phase());
        assertEquals(LaunchOptions.Role.VIEWER, s.role());
    }

    @Test
    void connectReachesActiveAndSnapshotShowsPeerAndScreen() throws Exception {
        ServerCore core = core();
        AppRuntime rt = client(core, new FakeAdapterFactory(), AppRuntime.Seams.none());
        assertEquals(ConnectOutcome.STARTED, rt.connect(" 127.0.0.1 ", code(core)));
        Snapshot s = await(rt, x -> x.phase() == UiPhase.ACTIVE);
        assertEquals("127.0.0.1", s.peer());
        assertEquals(1920, s.screenWidth());
        assertEquals(1080, s.screenHeight());
        assertEquals(1, s.generation());
        assertEquals(LaunchOptions.Role.VIEWER, s.role());
        assertNull(s.code(), "the Client never shows a code");
        assertFalse(s.controlEnabled() && s.frame() == null);
        assertEquals("", s.message());
        awaitTrue("frames sent", () -> rt.snapshot().frameCount() > 0);
    }

    @Test
    void doubleConnectSecondIsBusy() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        FakeAdapterFactory.Gate gate = adapters.blockNewScreenSource();
        adapterGates.add(gate);
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertTrue(gate.awaitEntered());
        assertEquals(UiPhase.ATTACHING, rt.snapshot().phase());
        assertEquals(ConnectOutcome.BUSY, rt.connect("127.0.0.1", code(core)));
        assertEquals(1, adapters.newScreenCalls());
        gate.open();
        await(rt, s -> s.phase() == UiPhase.ACTIVE);
        assertEquals(ConnectOutcome.BUSY, rt.connect("127.0.0.1", code(core)), "BUSY while the session lives");
    }

    @Test
    void concurrentConnectsExactlyOneStarts() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        int callers = 8;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Future<ConnectOutcome>> results = new ArrayList<>();
        String code = code(core);
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                barrier.await(WAIT_S, TimeUnit.SECONDS);
                return rt.connect("127.0.0.1", code);
            }));
        }
        int started = 0;
        for (Future<ConnectOutcome> f : results) {
            ConnectOutcome outcome = f.get(WAIT_S, TimeUnit.SECONDS);
            if (outcome == ConnectOutcome.STARTED) started++;
            else assertEquals(ConnectOutcome.BUSY, outcome);
        }
        assertEquals(1, started);
        await(rt, s -> s.phase() == UiPhase.ACTIVE);
        assertEquals(1, adapters.newScreenCalls());
        assertEquals(1, rt.snapshot().generation());
    }

    // ---- cancel ----------------------------------------------------------------------------

    @Test
    void viewerCreatesNoInputResourcesAndReconnectsAfterLocalCleanup() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        FakeAdapterFactory.Gate gate = adapters.blockNewScreenSource(true);
        adapterGates.add(gate);
        AtomicInteger connectorCalls = new AtomicInteger();
        AtomicInteger senderStarts = new AtomicInteger();
        hostResources.beforeSenderStart = senderStarts::incrementAndGet;
        SocketConnector counting = (socket, address, timeoutMs) -> {
            connectorCalls.incrementAndGet();
            socket.connect(address, timeoutMs);
        };
        AppRuntime rt = client(core, adapters, seams(counting, ClientHooks.none()));
        assertNull(rt.inputExecutor(), "Viewer has no local native input resources");
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertTrue(gate.awaitEntered());
        ClientSession viewer = session(rt);
        awaitTrue("image channel handed over", viewer::imageChannelReady);
        ServerSession host = core.sessions().current().orElseThrow();
        assertTrue(rt.cancel());
        assertFalse(rt.cancel(), "already cancelled");
        get(viewer.terminated());
        get(host.socketsClosed());
        assertFalse(host.terminated().isDone(), "Host prepare ignores interrupt until its owner opens the gate");
        assertEquals(2, connectorCalls.get());
        assertEquals(0, senderStarts.get());
        assertEquals(0, host.inputBeginCount());
        assertTrue(adapters.device.log().isEmpty());
        Snapshot idle = await(rt, snap -> snap.phase() == UiPhase.IDLE);
        assertEquals("Đã ngắt kết nối.", idle.message());
        assertEquals(Snapshot.Cleanup.NONE, idle.cleanup());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)), "Viewer cleanup is independent of Host release");
        Snapshot busy = await(rt, snap -> snap.phase() == UiPhase.IDLE && snap.message().equals(Messages.forAuth(Packet.AuthStatus.BUSY)));
        assertEquals(Snapshot.Cleanup.NONE, busy.cleanup());
        gate.open();
        get(host.terminated());
        get(host.inputReleased());
        awaitTrue("Host slot available", () -> core.sessions().current().isEmpty());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertEquals(3, await(rt, snap -> snap.phase() == UiPhase.ACTIVE).generation());
    }

    enum PreActive { CONNECTING, AUTHENTICATING, ATTACHING }

    @ParameterizedTest
    @EnumSource(PreActive.class)
    void cancelInEachPreActivePhase(PreActive phase) throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt;
        UiPhase expected;
        switch (phase) {
            case CONNECTING -> {
                ServerCore core = core();
                BlockingConnector connector = new BlockingConnector();
                rt = client(core, adapters, seams(connector, null));
                assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
                assertTrue(connector.entered.await(WAIT_S, TimeUnit.SECONDS));
                expected = UiPhase.CONNECTING;
            }
            case AUTHENTICATING -> {
                ServerSocket silent = new ServerSocket(0, 1, LO); // accepts in the kernel, never answers
                resources.add(silent);
                rt = client(silent.getLocalPort(), 1, adapters, AppRuntime.Seams.none(), System::nanoTime);
                assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", "123456"));
                expected = UiPhase.AUTHENTICATING;
            }
            default -> {
                ServerCore core = core();
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                gates.add(release);
                ClientHooks hooks = new ClientHooks(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                rt = client(core, adapters, seams(null, hooks));
                assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
                assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS));
                expected = UiPhase.ATTACHING;
            }
        }
        await(rt, s -> s.phase() == expected);
        ClientSession cs = session(rt);

        assertTrue(rt.cancel());
        get(cs.terminated());
        get(cs.inputReleased());
        Snapshot idle = await(rt, s -> s.phase() == UiPhase.IDLE);
        assertEquals("Đã ngắt kết nối.", idle.message());
        assertEquals(0, cs.framesReceived());
        assertEquals(0, cs.framesReceived());
        assertSessionResourcesReleased(rt);
        assertTrue(adapters.device.log().isEmpty());
    }

    @Test
    void cancelWhenActiveReturnsFalseButDisconnectCloses() throws Exception {
        ServerCore core = core();
        AppRuntime rt = client(core, new FakeAdapterFactory(), AppRuntime.Seams.none());
        connectActive(rt, core);
        assertFalse(rt.cancel());
        assertEquals(UiPhase.ACTIVE, rt.snapshot().phase());
        ClientSession cs = session(rt);
        assertTrue(rt.disconnect());
        get(cs.terminated());
        Snapshot idle = await(rt, s -> s.phase() == UiPhase.IDLE);
        assertEquals("Đã ngắt kết nối.", idle.message());
        assertFalse(rt.disconnect(), "nothing left to close");
    }

    // ---- reconnect and release ---------------------------------------------------------------

    @Test
    void generationsIncreaseAcrossConnects() throws Exception {
        ServerCore core = core();
        AppRuntime rt = client(core, new FakeAdapterFactory(), AppRuntime.Seams.none());
        assertEquals(1, connectActive(rt, core));
        disconnectAndSettle(rt, core);
        assertEquals(2, connectActive(rt, core));
    }

    // A completion callback of an old session cannot overwrite a newer slot: the snapshot is derived from the
    // current slot's session object and nothing registers a callback that writes runtime state. The
    // only way an old attempt could still be running when a new one starts is a prepare that was
    // cancelled but has not returned; connect() answers BUSY until it ended, which
    // cancelBeforeSessionCreatedNeverStarts forces with a latch.

    @Test
    void wrongCodeShownAsMessageAndPhaseReturnsToIdle() throws Exception {
        ServerCore core = core();
        AppRuntime rt = client(core, new FakeAdapterFactory(), AppRuntime.Seams.none());
        String real = code(core);
        String wrong = real.equals("000000") ? "111111" : "000000";
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", wrong));
        ClientSession cs = session(rt);
        get(cs.terminated());
        get(cs.inputReleased());
        Snapshot s = await(rt, x -> x.phase() == UiPhase.IDLE);
        assertEquals("Mã không đúng.", s.message());
        assertEquals(0, s.generation());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)), "a retry is allowed");
        await(rt, x -> x.phase() == UiPhase.ACTIVE);
    }








}
