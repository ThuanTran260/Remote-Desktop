package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.client.AdapterException;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.Packet;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerSession;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.InterruptedIOException;
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

    @AfterEach
    void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        adapterGates.forEach(FakeAdapterFactory.Gate::open);
        for (AutoCloseable r : resources) r.close();
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
        for (ServerCore c : cores) c.close();
        pool.shutdownNow();
        awaitNoNewRdThreads(baseline); // nothing this test started may outlive it
    }

    // ---- fixtures --------------------------------------------------------------------------

    private ServerCore core() throws Exception {
        ServerCore c = new ServerCore(new ServerConfig(LO, 0, 0, TestTimeouts.relaxed(), System::nanoTime,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    private AppRuntime client(ServerCore core, FakeAdapterFactory adapters, AppRuntime.Seams seams) throws Exception {
        return client(core.cmdPort(), core.imgPort(), adapters, seams, System::nanoTime);
    }

    private AppRuntime client(int cmdPort, int imgPort, FakeAdapterFactory adapters, AppRuntime.Seams seams,
            java.util.function.LongSupplier clock) throws Exception {
        AppRuntime rt = AppRuntime.forClient(clientConfig(cmdPort, imgPort, clock), adapters, seams);
        runtimes.add(rt);
        return rt;
    }

    private static AppRuntime.Seams seams(SocketConnector connector, ClientHooks hooks) {
        return new AppRuntime.Seams(hooks == null ? ClientHooks.none() : hooks,
                connector == null ? SocketConnector.DEFAULT : connector, UnaryOperator.identity(), () -> { },
                () -> { });
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
    void forClientAdapterFailureThrowsAndStartsNoThread() {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        adapters.failInputDevice(new AdapterException(AdapterException.Kind.HEADLESS, null));
        Set<Thread> before = liveRdThreads();
        AdapterException e = assertThrows(AdapterException.class,
                () -> AppRuntime.forClient(clientConfig(1, 2, System::nanoTime), adapters));
        assertEquals(AdapterException.Kind.HEADLESS, e.kind());
        Set<Thread> after = liveRdThreads();
        after.removeAll(before);
        assertTrue(after.isEmpty(), "no thread may be left behind: " + after);
        assertEquals(0, adapters.refreshCalls());
        assertEquals(0, adapters.newScreenCalls());
    }

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
        assertEquals(LaunchOptions.Role.CLIENT, s.role());
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
        assertEquals(LaunchOptions.Role.CLIENT, s.role());
        assertNull(s.code(), "the Client never shows a code");
        assertFalse(s.controlEnabled());
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
        assertEquals(UiPhase.CONNECTING, rt.snapshot().phase());
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
    void cancelBeforeSessionCreatedNeverStarts() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        FakeAdapterFactory.Gate gate = adapters.blockNewScreenSource();
        adapterGates.add(gate);
        AtomicInteger connectorCalls = new AtomicInteger();
        AtomicInteger senderStarts = new AtomicInteger();
        SocketConnector counting = (socket, address, timeoutMs) -> {
            connectorCalls.incrementAndGet();
            socket.connect(address, timeoutMs);
        };
        ClientHooks hooks = new ClientHooks(null, senderStarts::incrementAndGet);
        AppRuntime rt = client(core, adapters, seams(counting, hooks));

        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertTrue(gate.awaitEntered());
        assertTrue(rt.cancel());
        assertFalse(rt.cancel(), "already cancelled");
        assertEquals(ConnectOutcome.BUSY, rt.connect("127.0.0.1", code(core)), "the old connect is still winding down");
        gate.open();

        Snapshot idle = await(rt, s -> s.phase() == UiPhase.IDLE);
        assertEquals("Đã ngắt kết nối.", idle.message());
        assertEquals(0, connectorCalls.get(), "no socket was ever connected");
        assertEquals(0, senderStarts.get());
        assertTrue(rt.clientSession().isEmpty(), "no session object was published");
        assertTrue(core.sessions().current().isEmpty());
        assertTrue(adapters.device.log().isEmpty());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)), "connect works again");
        await(rt, s -> s.phase() == UiPhase.ACTIVE);
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
                }, null);
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
        assertEquals(0, cs.inputBeginCount());
        assertEquals(0, cs.senderRuns());
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

    private void holdKey(ServerCore core, FakeAdapterFactory adapters, int key) throws Exception {
        ServerSession server = core.sessions().current().orElseThrow();
        awaitTrue("control enabled", server::controlEnabled);
        server.offerInput(Packet.keyPress(key));
        assertTrue(adapters.device.awaitCall("keyPress:" + key));
    }

    @Test
    void reconnectBlockedUntilTerminatedAndInputReleased() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        OffsetClock clock = new OffsetClock();
        AppRuntime rt = client(core.cmdPort(), core.imgPort(), adapters, AppRuntime.Seams.none(), clock);
        connectActive(rt, core);
        holdKey(core, adapters, 17);
        com.ltm.rd.client.FakeInputDevice.Gate releaseGate = adapters.device.blockOn("keyRelease:17");
        ClientSession cs = session(rt);

        assertTrue(rt.disconnect());
        assertTrue(releaseGate.awaitEntered(), "the input worker is parked inside keyRelease:17");
        assertEquals(ConnectOutcome.WAITING_RELEASE, rt.connect("127.0.0.1", code(core)));
        clock.advance(Duration.ofSeconds(10));
        assertEquals(ConnectOutcome.WAITING_RELEASE, rt.connect("127.0.0.1", code(core)),
                "no timeout may reopen the button");
        assertFalse(cs.inputReleased().isDone());
        assertEquals(UiPhase.CLOSING, rt.snapshot().phase());

        releaseGate.open();
        get(cs.inputReleased());
        get(cs.terminated());
        awaitTrue("server slot", () -> core.sessions().current().isEmpty());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertEquals(2, await(rt, s -> s.phase() == UiPhase.ACTIVE).generation());
    }

    @Test
    void reconnectAfterReleaseFailureIsInputFaulted() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        connectActive(rt, core);
        holdKey(core, adapters, 17);
        adapters.device.failOn("keyRelease:17");
        ClientSession cs = session(rt);

        assertTrue(rt.disconnect());
        assertThrows(ExecutionException.class, () -> get(cs.inputReleased()));
        get(cs.terminated());
        awaitTrue("server slot", () -> core.sessions().current().isEmpty());

        assertEquals(ConnectOutcome.INPUT_FAULTED, rt.connect("127.0.0.1", code(core)));
        Snapshot failed = rt.snapshot();
        assertEquals(UiPhase.FAILED, failed.phase());
        assertEquals(Messages.INPUT_RELEASE_FAILED, failed.message());
        assertEquals(ConnectOutcome.INPUT_FAULTED, rt.connect("127.0.0.1", code(core)), "stays faulted");
        assertEquals(UiPhase.FAILED, rt.snapshot().phase());
        assertEquals(1, adapters.newScreenCalls(), "the second connect never reached the adapters");
    }

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

    @Test
    void adapterFailureOnConnectSetsMessageAndIdle() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        adapters.failNewScreenSource(new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, null));
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        Snapshot s = await(rt, x -> x.phase() == UiPhase.IDLE && !x.message().isEmpty());
        assertEquals("Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành.", s.message());
        assertEquals(0, s.generation());
        assertTrue(core.sessions().current().isEmpty());
        assertEquals(0, rt.tickerRegistrations(), "a failed prepare registered nothing");

        adapters.failNewScreenSource(null);
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)), "connect is allowed again");
        assertEquals(2, await(rt, x -> x.phase() == UiPhase.ACTIVE).generation(), "a used generation is not reused");
    }

    @Test
    void inputExecutorKeptAcrossReconnects() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        InputExecutor executor = rt.inputExecutor();
        for (int i = 1; i <= 3; i++) {
            assertEquals(i, connectActive(rt, core));
            disconnectAndSettle(rt, core);
        }
        assertSame(executor, rt.inputExecutor());
        assertEquals(InputExecutor.State.IDLE, executor.state());
        assertEquals(1, adapters.inputDeviceCalls());
        assertEquals(3, adapters.refreshCalls());
        assertEquals(3, adapters.newScreenCalls());
    }

    @Test
    void inputDeviceFaultMidSessionClosesSessionAndStaysFaulted() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime rt = client(core, adapters, AppRuntime.Seams.none());
        connectActive(rt, core);
        adapters.device.failOn("keyPress:65");
        ClientSession cs = session(rt);
        holdKey(core, adapters, 65); // the device throws on this key: the executor faults mid-session

        get(cs.terminated()); // the runtime closed the session itself
        assertTrue(cs.lifecycle().isClosed());
        assertThrows(ExecutionException.class, () -> get(cs.inputReleased()));
        Snapshot failed = await(rt, s -> s.phase() == UiPhase.FAILED);
        assertEquals(Messages.INPUT_FAULTED, failed.message());
        assertEquals(ConnectOutcome.INPUT_FAULTED, rt.connect("127.0.0.1", code(core)));
        assertEquals(UiPhase.FAILED, rt.snapshot().phase(), "stays faulted");
        assertEquals(1, adapters.newScreenCalls());
    }

    @Test
    void shutdownWithStuckPrepareReportsWorkersNotStopped() throws Exception {
        ServerCore core = core();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        FakeAdapterFactory.Gate gate = adapters.blockNewScreenSource(true);
        adapterGates.add(gate);
        AppRuntime rt = AppRuntime.forClient(
                clientConfig(core.cmdPort(), core.imgPort(), System::nanoTime, Duration.ofMillis(300)), adapters);
        runtimes.add(rt);
        assertEquals(ConnectOutcome.STARTED, rt.connect("127.0.0.1", code(core)));
        assertTrue(gate.awaitEntered());

        ShutdownReport report = get(rt.shutdownAsync());
        assertFalse(report.workersStopped(), "rd-app is still parked inside the adapter");
        gate.open();
    }
}
