package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.it.RawPeer;
import java.awt.image.BufferedImage;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The Server role of the runtime against a real HostCore on ephemeral loopback ports. */
@Timeout(120)
class AppRuntimeHostTest {
    private final List<AppRuntime> runtimes = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();

    private Set<Thread> baseline;

    @BeforeEach
    void captureThreads() {
        baseline = liveRdThreads();
    }

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable r : resources) r.close();
        cleanupActions.forEach(Runnable::run);
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
        awaitNoNewRdThreads(baseline); // nothing this test started may outlive it
    }

    private AppRuntime server(int cmdPort) throws Exception {
        AppRuntime rt = AppRuntime.forHost(hostConfig(System::nanoTime, cmdPort, 0), new FakeAdapterFactory());
        runtimes.add(rt);
        return rt;
    }

    private AppRuntime client(Snapshot listening, FakeAdapterFactory adapters) throws Exception {
        AppRuntime rt = AppRuntime.forViewer(viewerConfig(listening.cmdPort(), listening.imgPort(), System::nanoTime));
        runtimes.add(rt);
        return rt;
    }

    private AppRuntime startedServer() throws Exception {
        AppRuntime rt = server(0);
        get(rt.startHost());
        return rt;
    }

    private AppRuntime connectedClient(AppRuntime server, FakeAdapterFactory adapters) throws Exception {
        Snapshot listening = server.snapshot();
        AppRuntime client = client(listening, adapters);
        assertEquals(ConnectOutcome.STARTED, client.connect("127.0.0.1", listening.code()));
        await(server, s -> s.phase() == UiPhase.ACTIVE);
        return client;
    }

    @Test
    void startServerReachesListening() throws Exception {
        AppRuntime rt = server(0);
        assertEquals(UiPhase.IDLE, rt.snapshot().phase());
        assertEquals(LaunchOptions.Role.HOST, rt.role());
        get(rt.startHost());
        Snapshot s = rt.snapshot();
        assertEquals(UiPhase.LISTENING, s.phase());
        assertTrue(s.cmdPort() > 0);
        assertTrue(s.imgPort() > 0);
        assertTrue(s.code().matches("\\d{6}"), "a six digit code");
        assertEquals(0, s.generation());
        assertFalse(s.controlEnabled());
        assertNull(s.frame());
        assertEquals("", s.message());
    }

    @Test
    void startServerPortInUseFailsThenRetrySucceeds() throws Exception {
        ServerSocket occupied = new ServerSocket(0, 1, LO);
        resources.add(occupied);
        int port = occupied.getLocalPort();
        AppRuntime rt = server(port);

        ExecutionException e = assertThrows(ExecutionException.class, () -> get(rt.startHost()));
        assertInstanceOf(BindException.class, e.getCause());
        Snapshot failed = rt.snapshot();
        assertEquals(UiPhase.FAILED, failed.phase());
        assertEquals("Cổng đã được sử dụng. Đổi bằng --cmd-port/--img-port.", failed.message());
        assertNull(failed.code());
        assertEquals(0, rt.tickerRegistrations(), "the failed core registered nothing that is still live");
        assertNull(rt.hostCore(), "the failed core was dropped");

        occupied.close();
        get(rt.startHost()); // a new HostCore: the failed one cannot be reused
        Snapshot listening = rt.snapshot();
        assertEquals(UiPhase.LISTENING, listening.phase());
        assertEquals(port, listening.cmdPort());
        assertEquals("", listening.message());
    }

    @Test
    void startServerWhileListeningFails() throws Exception {
        AppRuntime rt = startedServer();
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(rt.startHost()));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(UiPhase.LISTENING, rt.snapshot().phase());
    }

    @Test
    void regenerateCodeOnlyWhenListening() throws Exception {
        AppRuntime rt = server(0);
        assertFalse(rt.regenerateCode(), "not listening yet");
        get(rt.startHost());
        String first = rt.snapshot().code();
        assertTrue(rt.regenerateCode());
        assertNotEquals(first, rt.snapshot().code());

        // RESERVED: a peer that authenticated but has not attached yet
        Snapshot listening = rt.snapshot();
        try (RawPeer peer = RawPeer.connect(listening.cmdPort())) {
            peer.send(Packet.auth(listening.code()));
            peer.readUntil(PacketType.AUTH_RESULT);
            Snapshot reserved = await(rt, s -> s.phase() == UiPhase.RESERVED);
            assertFalse(rt.regenerateCode());
            assertNull(rt.snapshot().code());
            assertNull(reserved.code());
        }
        await(rt, s -> s.phase() == UiPhase.LISTENING);

        // ACTIVE
        connectedClient(rt, new FakeAdapterFactory());
        String active = rt.snapshot().code();
        assertFalse(rt.regenerateCode());
        assertEquals(active, rt.snapshot().code());
    }

    @Test
    void snapshotToStringNeverContainsCodeOrPeer() throws Exception {
        Snapshot synthetic = new Snapshot(LaunchOptions.Role.HOST, UiPhase.ACTIVE, 3, 7, "msg", "10.20.30.40", 5001,
                5000, "482913", 1920, 1080, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), 9, true,
                TrafficStats.ZERO, Snapshot.Cleanup.NONE);
        String text = synthetic.toString();
        assertFalse(text.contains("482913"));
        assertFalse(text.contains("10.20.30.40"));
        assertFalse(text.contains("BufferedImage"));
        assertTrue(text.contains("HOST") && text.contains("ACTIVE"));

        AppRuntime rt = startedServer();
        String code = rt.snapshot().code();
        connectedClient(rt, new FakeAdapterFactory());
        Snapshot real = rt.snapshot();
        assertEquals("127.0.0.1", real.peer());
        assertNull(real.code());
        assertFalse(real.toString().contains(code));
        assertFalse(real.toString().contains(real.peer()));
    }

    @Test
    void disconnectReturnsToListeningWithNewCode() throws Exception {
        AppRuntime rt = startedServer();
        int idleTickers = rt.tickerRegistrations();
        connectedClient(rt, new FakeAdapterFactory());
        Snapshot active = await(rt, s -> s.phase() == UiPhase.ACTIVE);
        assertEquals(1, active.generation());
        assertEquals(1920, active.screenWidth());
        assertEquals(1080, active.screenHeight());

        assertTrue(rt.disconnect());
        Snapshot back = await(rt, s -> s.phase() == UiPhase.LISTENING);
        assertNotEquals(active.code(), back.code());
        assertEquals(0, back.generation());
        assertEquals("Đã ngắt kết nối.", back.message());
        assertFalse(rt.disconnect(), "nothing left to close");
        awaitTrue("ticker registrations back to the idle count", () -> rt.tickerRegistrations() == idleTickers);
        awaitNoSessionThreads(baseline);
    }

    @Test
    void sendInputOnWrongRoleReturnsWrongRole() throws Exception {
        AppRuntime server = startedServer();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime client = client(server.snapshot(), adapters);
        assertEquals(SendResult.WRONG_ROLE, server.sendInput(1, 0, Packet.keyPress(65)));
        assertFalse(client.releaseControlInput(1));
        assertFalse(client.regenerateCode());
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(client.startHost()));
        assertInstanceOf(IllegalStateException.class, e.getCause());

        assertEquals(ConnectOutcome.WRONG_ROLE, server.connect("127.0.0.1", "123456"));
        assertFalse(server.cancel());
        assertEquals(SendResult.NO_SESSION, client.sendInput(1, 0, Packet.keyPress(65)));
        assertTrue(adapters.device.log().isEmpty());
    }

    private final List<Runnable> cleanupActions = new ArrayList<>();

    private AppRuntime host(FakeAdapterFactory adapters) throws Exception {
        return host(adapters, System::nanoTime, java.time.Duration.ofSeconds(20));
    }

    private AppRuntime host(FakeAdapterFactory adapters, java.util.function.LongSupplier clock,
            java.time.Duration budget) throws Exception {
        AppRuntime host = AppRuntime.forHost(hostConfig(clock, 0, 0, budget), adapters);
        runtimes.add(host);
        get(host.startHost());
        return host;
    }

    private AppRuntime viewer(AppRuntime host) {
        Snapshot listening = host.snapshot();
        AppRuntime viewer = AppRuntime.forViewer(viewerConfig(listening.cmdPort(), listening.imgPort(), System::nanoTime));
        runtimes.add(viewer);
        return viewer;
    }

    private Snapshot active(AppRuntime host, AppRuntime viewer) {
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", host.snapshot().code()));
        Snapshot active = await(viewer, snap -> snap.phase() == UiPhase.ACTIVE);
        assertEquals(UiPhase.ACTIVE, host.snapshot().phase());
        return active;
    }

    private void holdKey(AppRuntime host, AppRuntime viewer, FakeAdapterFactory adapters, int key) throws Exception {
        Snapshot snapshot = await(viewer, Snapshot::controlEnabled);
        assertEquals(SendResult.SENT, viewer.sendInput(snapshot.generation(), snapshot.inputEpoch(), Packet.keyPress(key)));
        assertTrue(adapters.device.awaitCall("keyPress:" + key));
    }

    @Test void forHostAdapterFailureThrowsAndStartsNoThread() {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        adapters.failInputDevice(new com.ltm.rd.host.AdapterException(com.ltm.rd.host.AdapterException.Kind.HEADLESS, null));
        Set<Thread> before = liveRdThreads();
        var failure = assertThrows(com.ltm.rd.host.AdapterException.class,
                () -> AppRuntime.forHost(hostConfig(System::nanoTime, 0, 0), adapters));
        assertEquals(com.ltm.rd.host.AdapterException.Kind.HEADLESS, failure.kind());
        Set<Thread> after = liveRdThreads();
        after.removeAll(before);
        assertTrue(after.isEmpty(), "no thread may be left behind: " + after);
        assertEquals(0, adapters.refreshCalls());
        assertEquals(0, adapters.newScreenCalls());
        awaitNoNewRdThreads(before);
    }

    @Test void slotHeldWhileInputReleaseIsHeld() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        holdKey(host, viewer, adapters, 17);
        var session = host.hostCore().sessions().current().orElseThrow();
        var released = session.inputReleased();
        var gate = adapters.device.blockOn("keyRelease:17");
        cleanupActions.add(gate::open);
        String code = host.hostCore().sessions().currentCode();
        var previousViewer = viewer.viewerSession().orElseThrow();
        assertTrue(viewer.disconnect());
        assertTrue(gate.awaitEntered());
        assertSame(session, host.hostCore().sessions().current().orElseThrow());
        assertSame(released, session.inputReleased());
        assertFalse(released.isDone());
        Snapshot pending = host.snapshot();
        assertEquals(Snapshot.Cleanup.INPUT_PENDING, pending.cleanup());
        assertNull(pending.code());
        assertFalse(host.regenerateCode());
        assertEquals(Packet.AuthStatus.BUSY, host.hostCore().sessions().tryReserve(LO, 2, code).status());
        gate.open();
        get(released);
        get(session.terminated());
        await(host, snap -> snap.phase() == UiPhase.LISTENING);
        assertNotEquals(code, host.snapshot().code());
        // Host readiness does not imply that the previous Viewer's workers have stopped.
        get(previousViewer.socketsClosed());
        get(previousViewer.terminated());
        active(host, viewer);
        assertEquals(2, viewer.snapshot().generation());
    }

    @Test void releaseFailureKeepsBusyAndDisablesRetry() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        holdKey(host, viewer, adapters, 17);
        var session = host.hostCore().sessions().current().orElseThrow();
        String code = host.hostCore().sessions().currentCode();
        adapters.device.failOn("keyRelease:17");
        assertTrue(viewer.disconnect());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> get(session.inputReleased()));
        get(session.terminated());
        Snapshot failed = host.snapshot();
        assertEquals(Snapshot.Cleanup.INPUT_FAILED, failed.cleanup());
        assertEquals(Messages.INPUT_RELEASE_FAILED, failed.message());
        assertNull(failed.code());
        assertEquals(com.ltm.rd.host.InputExecutor.State.FAULTED, host.inputExecutor().state());
        assertFalse(host.regenerateCode());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> get(host.startHost()));
        assertEquals(Packet.AuthStatus.BUSY, host.hostCore().sessions().tryReserve(LO, 2, code).status());
        assertEquals(1, adapters.newScreenCalls());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> preparationFailures() {
        return java.util.stream.Stream.of(true, false).flatMap(refresh -> java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(refresh, new com.ltm.rd.host.AdapterException(
                        com.ltm.rd.host.AdapterException.Kind.ROBOT_UNAVAILABLE, null)),
                org.junit.jupiter.params.provider.Arguments.of(refresh, new IllegalStateException("secret adapter detail"))));
    }

    @ParameterizedTest
    @MethodSource("preparationFailures")
    void prepareFailureReturnsSlotWithoutBegin(boolean refresh, Exception failure) throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        adapters.failPreparation(refresh, failure);
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        String code = host.snapshot().code();
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", code));
        await(viewer, snap -> snap.phase() == UiPhase.IDLE && !snap.message().isEmpty());
        Snapshot listening = await(host, snap -> snap.phase() == UiPhase.LISTENING && !snap.message().isEmpty());
        assertEquals(0, listening.generation());
        assertTrue(host.hostCore().sessions().current().isEmpty());
        assertEquals(1, host.tickerRegistrations(), "only the unchanged pending sweep remains");
        assertEquals(0, viewer.tickerRegistrations(), "the failed attempt leaves no Viewer registration");
        assertEquals(failure instanceof com.ltm.rd.host.AdapterException
                ? "Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành."
                : "Lỗi không xác định. Xem nhật ký để biết loại lỗi.", listening.message());
        assertEquals(com.ltm.rd.host.InputExecutor.State.IDLE, host.inputExecutor().state());
        assertNotEquals(code, listening.code());
        adapters.failPreparation(refresh, null);
        active(host, viewer);
        assertEquals(2, viewer.snapshot().generation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAdapterFailurePreservesTimeout(boolean runtimeFailure) throws Exception {
        var clock = new com.ltm.rd.common.FakeClock();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        var gate = adapters.blockNewScreenSource(true);
        cleanupActions.add(gate::open);
        adapters.failPreparation(false, runtimeFailure ? new IllegalStateException("late secret detail")
                : new com.ltm.rd.host.AdapterException(com.ltm.rd.host.AdapterException.Kind.ROBOT_UNAVAILABLE, null));
        AppRuntime host = host(adapters, clock, java.time.Duration.ofSeconds(20));
        AppRuntime viewer = viewer(host);
        String code = host.snapshot().code();
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", code));
        assertTrue(gate.awaitEntered());
        var session = host.hostCore().sessions().current().orElseThrow();
        long deadline = session.reservedAtNanos() + hostConfig(clock, 0, 0).timeouts().reserve().toNanos();
        clock.set(deadline);
        session.tick(deadline);
        var first = session.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.TIMEOUT, first.reason());
        assertEquals("attach timeout", first.message());
        assertFalse(session.terminated().isDone());
        assertEquals(Packet.AuthStatus.BUSY, host.hostCore().sessions().tryReserve(LO, 2, code).status());
        gate.open();
        get(session.terminated());
        get(session.inputReleased());
        assertSame(first, session.closeInfo().orElseThrow());
        assertTrue(session.preparationFailure().isEmpty());
        assertTrue(session.screen().isEmpty());
        assertEquals(0, session.inputBeginCount());
        assertEquals(0, session.senderRuns());
        await(host, snap -> snap.phase() == UiPhase.LISTENING);
        assertNotEquals(code, host.snapshot().code());
    }

    @Test void fastPrepareFailureBeforeFirstSessionSnapshotRetainsMessage() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        adapters.failNewScreenSource(new com.ltm.rd.host.AdapterException(
                com.ltm.rd.host.AdapterException.Kind.ROBOT_UNAVAILABLE, null));
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", host.snapshot().code()));
        awaitTrue("Viewer session exists", () -> viewer.viewerSession().isPresent());
        get(viewer.viewerSession().orElseThrow().terminated());
        awaitTrue("Host slot released before its first session snapshot", () -> host.hostCore().sessions().current().isEmpty());
        Snapshot first = host.snapshot();
        assertEquals(UiPhase.LISTENING, first.phase());
        assertEquals("Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành.", first.message());
    }

    @Test void inputExecutorKeptAcrossReconnects() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        var executor = host.inputExecutor();
        for (int i = 1; i <= 3; i++) {
            assertEquals(i, active(host, viewer).generation());
            assertTrue(viewer.disconnect());
            await(viewer, snap -> snap.phase() == UiPhase.IDLE);
            await(host, snap -> snap.phase() == UiPhase.LISTENING);
        }
        assertSame(executor, host.inputExecutor());
        assertEquals(com.ltm.rd.host.InputExecutor.State.IDLE, executor.state());
        assertEquals(1, adapters.inputDeviceCalls());
        assertEquals(3, adapters.refreshCalls());
        assertEquals(3, adapters.newScreenCalls());
    }

    @Test void inputDeviceFaultMidSessionClosesSessionAndStaysFaulted() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        var session = host.hostCore().sessions().current().orElseThrow();
        adapters.device.failOn("keyPress:65");
        holdKey(host, viewer, adapters, 65);
        get(session.terminated());
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, session.closeInfo().orElseThrow().reason());
        assertEquals("input fault", session.closeInfo().orElseThrow().message());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> get(session.inputReleased()));
        Snapshot failed = host.snapshot();
        assertEquals(Snapshot.Cleanup.INPUT_FAULTED, failed.cleanup());
        assertEquals(Messages.INPUT_FAULTED, failed.message());
        assertFalse(host.regenerateCode());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> get(host.startHost()));
        assertEquals(1, adapters.newScreenCalls());
    }

    @Test void shutdownWithStuckPrepareReportsWorkersNotStopped() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        var gate = adapters.blockNewScreenSource(true);
        cleanupActions.add(gate::open);
        AppRuntime host = host(adapters, System::nanoTime, java.time.Duration.ofMillis(300));
        AppRuntime viewer = viewer(host);
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", host.snapshot().code()));
        assertTrue(gate.awaitEntered());
        var session = host.hostCore().sessions().current().orElseThrow();
        ShutdownReport report = get(host.shutdownAsync());
        assertTrue(report.socketsClosed());
        assertFalse(report.workersStopped(), "rd-host-prepare is still parked inside the adapter");
        assertEquals(InputRelease.NOT_NEEDED, report.inputRelease());
        assertFalse(session.terminated().isDone());
        gate.open();
        get(session.terminated());
    }

    @Test void geometryRefreshedPerHostSession() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        adapters.inputState = () -> host.inputExecutor().state().name();
        AppRuntime viewer = viewer(host);
        Snapshot first = active(host, viewer);
        assertEquals(1920, first.screenWidth());
        assertEquals(1080, first.screenHeight());
        assertTrue(viewer.disconnect());
        await(viewer, snap -> snap.phase() == UiPhase.IDLE);
        await(host, snap -> snap.phase() == UiPhase.LISTENING);
        adapters.nextGeometry(1280, 720);
        Snapshot second = active(host, viewer);
        assertEquals(1280, second.screenWidth());
        assertEquals(720, second.screenHeight());
        long generation = host.hostCore().sessions().current().orElseThrow().generation();
        assertEquals(com.ltm.rd.host.SubmitResult.ACCEPTED, host.inputExecutor().submit(generation,
                new com.ltm.rd.host.InputCommand.MouseMove(1919, 1079)));
        assertEquals(com.ltm.rd.host.SubmitResult.ACCEPTED, host.inputExecutor().submit(generation,
                new com.ltm.rd.host.InputCommand.Wheel(9999)));
        assertTrue(adapters.device.awaitCall("mouseWheel:9999"), "FIFO drain after the old-bounds move");
        assertFalse(adapters.device.log().contains("mouseMove:1919,1079"), "the new executor bounds reject the stale point");
        assertEquals(com.ltm.rd.host.SubmitResult.ACCEPTED, host.inputExecutor().submit(generation,
                new com.ltm.rd.host.InputCommand.MouseMove(1279, 719)));
        assertTrue(adapters.device.awaitCall("mouseMove:1279,719"));
        CoordinateMapper.RemotePoint point = CoordinateMapper.map(1919, 1079, 1920, 1080,
                second.screenWidth(), second.screenHeight()).orElseThrow();
        assertEquals(new CoordinateMapper.RemotePoint(1279, 719), point);
        Snapshot control = await(viewer, Snapshot::controlEnabled);
        assertEquals(SendResult.SENT, viewer.sendInput(control.generation(), control.inputEpoch(),
                Packet.mouseMove(point.x(), point.y())));
        assertEquals(SendResult.SENT, viewer.sendInput(control.generation(), control.inputEpoch(), Packet.wheel(-1)));
        assertTrue(adapters.device.awaitCall("mouseWheel:-1"), "wire and native FIFO drain after the mapped point");
        assertEquals(2, adapters.device.log().stream().filter("mouseMove:1279,719"::equals).count());
        assertEquals(List.of("refresh:rd-host-prepare:IDLE", "screen:rd-host-prepare:IDLE",
                "refresh:rd-host-prepare:IDLE", "screen:rd-host-prepare:IDLE"), adapters.preparationCalls);
        assertEquals(1, adapters.inputDeviceCalls());
        assertEquals(2, adapters.refreshCalls());
        assertEquals(2, adapters.newScreenCalls());
    }

    @RepeatedTest(20)
    void preparePastReserveKeepsBusyAndDiscardsLateGeometry() throws Exception {
        com.ltm.rd.common.FakeClock clock = new com.ltm.rd.common.FakeClock();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        var gate = adapters.blockNewScreenSource(true);
        cleanupActions.add(gate::open);
        AppRuntime host = host(adapters, clock, java.time.Duration.ofSeconds(20));
        AppRuntime viewer = viewer(host);
        String code = host.snapshot().code();
        assertEquals(ConnectOutcome.STARTED, viewer.connect("127.0.0.1", code));
        assertTrue(gate.awaitEntered());
        var session = host.hostCore().sessions().current().orElseThrow();
        long deadline = session.reservedAtNanos() + hostConfig(clock, 0, 0).timeouts().reserve().toNanos();
        clock.set(deadline);
        session.tick(deadline);
        get(session.socketsClosed());
        assertFalse(session.terminated().isDone());
        assertSame(session, host.hostCore().sessions().current().orElseThrow());
        assertEquals(code, host.hostCore().sessions().currentCode());
        assertEquals(Packet.AuthStatus.BUSY, host.hostCore().sessions().tryReserve(LO, 2, code).status());
        assertEquals(Snapshot.Cleanup.WORKERS, host.snapshot().cleanup());
        assertNull(host.snapshot().code());
        gate.open();
        get(session.terminated());
        get(session.inputReleased());
        assertTrue(session.screen().isEmpty());
        assertTrue(session.preparationFailure().isEmpty());
        assertEquals(0, session.inputBeginCount());
        assertEquals(0, session.senderRuns());
        assertEquals(Packet.DisconnectReason.TIMEOUT, session.closeInfo().orElseThrow().reason());
        await(viewer, snap -> snap.phase() == UiPhase.IDLE);
        await(host, snap -> snap.phase() == UiPhase.LISTENING);
        assertNotEquals(code, host.snapshot().code());
        adapters.nextGeometry(1280, 720);
        Snapshot next = active(host, viewer);
        assertEquals(1280, next.screenWidth());
        assertEquals(720, next.screenHeight());
        assertEquals(2, adapters.refreshCalls());
        assertEquals(2, adapters.newScreenCalls());
    }

    @Test void inputFaultAfterUserClosePreservesFirstReasonAndKeepsBusy() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        holdKey(host, viewer, adapters, 17);
        var session = host.hostCore().sessions().current().orElseThrow();
        var released = session.inputReleased();
        var gate = adapters.device.blockOn("mouseWheel:1");
        cleanupActions.add(gate::open);
        adapters.device.failOn("mouseWheel:1");
        Snapshot snapshot = viewer.snapshot();
        assertEquals(SendResult.SENT, viewer.sendInput(snapshot.generation(), snapshot.inputEpoch(), Packet.wheel(1)));
        assertTrue(gate.awaitEntered());
        assertTrue(host.disconnect());
        assertEquals(Packet.DisconnectReason.USER, session.closeInfo().orElseThrow().reason());
        gate.open();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> get(released));
        get(session.terminated());
        assertSame(released, session.inputReleased());
        assertEquals(Packet.DisconnectReason.USER, session.closeInfo().orElseThrow().reason());
        assertEquals(1, session.inputBeginCount());
        assertEquals(1, adapters.device.log().stream().filter("keyRelease:17"::equals).count());
        assertEquals(Snapshot.Cleanup.INPUT_FAILED, host.snapshot().cleanup());
        assertSame(session, host.hostCore().sessions().current().orElseThrow());
        assertEquals(Packet.AuthStatus.BUSY, host.hostCore().sessions().tryReserve(LO, 2,
                host.hostCore().sessions().currentCode()).status());
    }

    private Runnable holdOutsideLocks(java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release) {
        cleanupActions.add(release::countDown);
        return () -> {
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        };
    }

    @Test void inFlightOpenSnapshotCannotEraseClosedReason() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable hold = holdOutsideLocks(entered, release);
        AppRuntime host = AppRuntime.forHost(hostConfig(System::nanoTime, 0, 0), new FakeAdapterFactory(),
                AppRuntime.Seams.none(), () -> { if (armed.compareAndSet(true, false)) hold.run(); }, () -> { });
        runtimes.add(host);
        get(host.startHost());
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        var session = host.hostCore().sessions().current().orElseThrow();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            armed.set(true);
            var snapshot = caller.submit(host::snapshot);
            assertTrue(entered.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
            var first = new com.ltm.rd.common.CloseInfo(Packet.DisconnectReason.TIMEOUT, "snapshot race timeout", false);
            assertTrue(session.close(first));
            get(session.socketsClosed());
            get(session.terminated());
            get(session.inputReleased());
            awaitTrue(() -> host.hostCore().sessions().current().isEmpty());
            assertSame(first, session.closeInfo().orElseThrow());
            assertEquals("Hết thời gian chờ phản hồi.", host.snapshot().message());
            release.countDown();
            assertEquals("", snapshot.get(WAIT_S, java.util.concurrent.TimeUnit.SECONDS).message(),
                    "the in-flight projection was captured while the session was still open");
            for (int i = 0; i < 3; i++) {
                Snapshot listening = host.snapshot();
                assertEquals(UiPhase.LISTENING, listening.phase());
                assertEquals("Hết thời gian chờ phản hồi.", listening.message());
            }
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void timeoutRacingHostDisconnectKeepsFirstReason() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AppRuntime host = AppRuntime.forHost(hostConfig(System::nanoTime, 0, 0), new FakeAdapterFactory(),
                AppRuntime.Seams.none(), () -> { }, holdOutsideLocks(entered, release));
        runtimes.add(host);
        get(host.startHost());
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        var session = host.hostCore().sessions().current().orElseThrow();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var disconnected = caller.submit(host::disconnect);
            assertTrue(entered.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
            var first = new com.ltm.rd.common.CloseInfo(Packet.DisconnectReason.TIMEOUT, "disconnect race timeout", false);
            assertTrue(session.close(first));
            get(session.socketsClosed());
            get(session.terminated());
            get(session.inputReleased());
            awaitTrue(() -> host.hostCore().sessions().current().isEmpty());
            assertEquals("Hết thời gian chờ phản hồi.", host.snapshot().message());
            release.countDown();
            boolean wonClose = disconnected.get(WAIT_S, java.util.concurrent.TimeUnit.SECONDS);
            assertSame(first, session.closeInfo().orElseThrow());
            for (int i = 0; i < 3; i++) {
                Snapshot listening = host.snapshot();
                assertEquals(UiPhase.LISTENING, listening.phase());
                assertEquals("Hết thời gian chờ phản hồi.", listening.message());
            }
            assertFalse(wonClose, "USER lost the real first-close decision");
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void delayedOldDisconnectAndObserverCannotOverwriteNewSessionReason() throws Exception {
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime host = host(adapters);
        AppRuntime viewer = viewer(host);
        active(host, viewer);
        var old = host.hostCore().sessions().current().orElseThrow();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        cleanupActions.add(release::countDown);
        com.ltm.rd.host.HostTestAccess.lifecycle(old).addCloseHook(() -> {
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        });
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var disconnected = caller.submit(host::disconnect);
            assertTrue(entered.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
            get(old.terminated());
            get(old.inputReleased());
            awaitTrue(() -> host.hostCore().sessions().current().isEmpty());
            await(viewer, snap -> snap.phase() == UiPhase.IDLE);
            active(host, viewer);
            var newer = host.hostCore().sessions().current().orElseThrow();
            assertTrue(newer.generation() > old.generation());
            newer.close(new com.ltm.rd.common.CloseInfo(Packet.DisconnectReason.TIMEOUT, "new timeout", false));
            get(newer.terminated());
            get(newer.inputReleased());
            awaitTrue(() -> host.hostCore().sessions().current().isEmpty());
            String expected = Messages.forClose(Packet.DisconnectReason.TIMEOUT);
            assertEquals(expected, host.snapshot().message());
            release.countDown();
            assertTrue(disconnected.get(WAIT_S, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(expected, host.snapshot().message());
            var observe = AppRuntime.class.getDeclaredMethod("observeSession", com.ltm.rd.host.HostSession.class);
            observe.setAccessible(true);
            observe.invoke(host, old);
            assertEquals(expected, host.snapshot().message());
        } finally { release.countDown(); caller.shutdownNow(); }
    }
}
