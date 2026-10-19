package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.server.FrameMonitor;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerSession;
import com.ltm.rd.server.ServerTestAccess;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Image stream end to end: real ClientSession and ScreenSender against the real ServerCore over
 * loopback, with a throttled image socket to produce stalls. Frame stall 600 ms, frame timeout
 * 2 s (10 s where another timeout is under test), image write limit 1.5 s. Every timeout test
 * asserts its exact cause, so a different timeout closing first fails it.
 */
@Timeout(180)
class ImageStreamIntegrationTest {
    private static final int WAIT_S = 10;
    private static final long SLACK_MS = 600;
    private static final Duration GRACE = Duration.ofMillis(300);
    private static final Duration IMG_WRITE_LIMIT = Duration.ofMillis(1500);

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
    private final FakeScreenSource screen = new FakeScreenSource(1920, 1080);
    private final AtomicReference<ThrottledOutputStream> throttle = new AtomicReference<>();
    private final List<ClientSession> clients = new ArrayList<>();
    private final List<ServerCore> cores = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final Set<Thread> threadsAtStart = sessionThreads();

    @AfterEach
    void teardown() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        for (ServerCore c : cores) c.close();
        for (ClientSession c : clients) c.cancel();
        for (ClientSession c : clients) c.terminated().get(5, TimeUnit.SECONDS);
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        ticker.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    // ---- fixtures --------------------------------------------------------------------------

    private static Timeouts timeouts(Duration frameTimeout) {
        return new Timeouts(Duration.ofMillis(1500), Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(3), Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofMillis(600), frameTimeout, Duration.ofSeconds(20), Duration.ofSeconds(5),
                IMG_WRITE_LIMIT, GRACE, Duration.ofSeconds(3), Duration.ZERO, Duration.ofSeconds(30),
                Duration.ofMillis(100));
    }

    private static Timeouts normal() {
        return timeouts(Duration.ofSeconds(2));
    }

    private ServerCore core(Timeouts t) throws IOException {
        ServerCore c = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, t, System::nanoTime,
                new SecureRandom()));
        cores.add(c);
        c.start();
        return c;
    }

    /** Starts a client whose image socket output goes through a throttle (unlimited until a test sets a rate). */
    private ClientSession startClient(ServerCore core, Timeouts t, FakeScreenSource source) {
        ClientConfig cfg = new ClientConfig("127.0.0.1", core.cmdPort(), core.imgPort(),
                core.sessions().currentCode(), t, System::nanoTime, out -> {
                    ThrottledOutputStream th = new ThrottledOutputStream(out);
                    throttle.set(th);
                    return th;
                });
        ClientSession c = new ClientSession(cfg, generations.incrementAndGet(), executor, source,
                SocketConnector.DEFAULT, ClientHooks.none(), ticker, scheduler, cleanup);
        clients.add(c);
        assertTrue(c.start());
        return c;
    }

    private ClientSession startClient(ServerCore core, Timeouts t) {
        return startClient(core, t, screen);
    }

    private static ServerSession serverSession(ServerCore core) {
        awaitTrue(() -> core.sessions().current().isPresent(), "the server session");
        return core.sessions().current().orElseThrow();
    }

    private static SessionLifecycle<?> lifecycle(ServerSession s) {
        return ServerTestAccess.lifecycle(s);
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

    /** Records when a close decision was taken and why, from a close hook. */
    private static final class CloseRecorder {
        final CountDownLatch decided = new CountDownLatch(1);
        volatile long decidedAtNanos;
        volatile CloseInfo info;

        CloseRecorder(SessionLifecycle<?> lc) {
            lc.addCloseHook(() -> {
                decidedAtNanos = System.nanoTime();
                info = lc.closeInfo().orElse(null);
                decided.countDown();
            });
        }

        void await() throws InterruptedException {
            assertTrue(decided.await(WAIT_S, TimeUnit.SECONDS), "session was never closed");
        }
    }

    private void awaitControl(ServerSession s) {
        awaitTrue(s::controlEnabled, "control to be enabled by the first frame");
    }

    private static Set<Thread> sessionThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && (t.getName().equals("rd-connect") || t.getName().equals("rd-cmd-reader")
                        || t.getName().startsWith("rd-cmd-writer-") || t.getName().equals("rd-frame-receiver")
                        || t.getName().equals("rd-screen-sender")))
                .collect(Collectors.toSet());
    }

    private void awaitNoNewSessionThreads() {
        awaitTrue(() -> {
            Set<Thread> now = sessionThreads();
            now.removeAll(threadsAtStart);
            return now.isEmpty();
        }, "all rd-* session threads to exit");
    }

    // ---- frames and control ----------------------------------------------------------------

    @Test
    void framesFlowAndControlEnablesAfterFirstFrame() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        awaitTrue(() -> s.framesReceived() >= 3, "several frames");
        assertTrue(s.latestFrame().isPresent());
    }

    @Test
    void inputReachesClientDeviceAfterFirstFrame() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        assertEquals(CommandWriter.OfferResult.ACCEPTED, s.offerInput(Packet.mousePress(100, 100, 1)));
        assertTrue(device.awaitCall("mouseMove:100,100"));
        assertTrue(device.awaitCall("mousePress:1"));
        List<String> log = device.log();
        assertTrue(log.indexOf("mouseMove:100,100") < log.indexOf("mousePress:1"), "move precedes press: " + log);
    }

    @Test
    void inputRejectedBeforeFirstFrame() throws Exception {
        ServerCore core = core(normal());
        CountDownLatch gate = screen.blockCaptures(); // the first capture, hence the first frame, is held back
        gates.add(gate);
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        assertFalse(s.controlEnabled());
        assertEquals(CommandWriter.OfferResult.CLOSED, s.offerInput(Packet.mouseMove(1, 1)));
        gate.countDown();
        awaitControl(s);
        assertEquals(CommandWriter.OfferResult.ACCEPTED, s.offerInput(Packet.mouseMove(1, 1)));
    }

    @Test
    void downscaledFrameIsAccepted() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitTrue(() -> s.latestFrame().isPresent(), "a frame");
        assertEquals(1280, s.latestFrame().orElseThrow().getWidth(), "1920x1080 is sent as 1280x720");
        assertEquals(720, s.latestFrame().orElseThrow().getHeight());
        assertFalse(lifecycle(s).isClosed());
    }

    @Test
    void slowLinkStallsOnceThenRecovers() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        assertEquals(CommandWriter.OfferResult.ACCEPTED, s.offerInput(Packet.keyPress(17)));
        assertTrue(device.awaitCall("keyPress:17"));

        List<FrameMonitor.Transition> events = new CopyOnWriteArrayList<>();
        s.addFrameObserver((revision, t) -> {
            events.add(t);
            if (t == FrameMonitor.Transition.STALL_START) throttle.get().setRate(Long.MAX_VALUE); // the link recovers
        });
        // exactly the next frame goes out slowly: far longer than frameStall, but restored by the observer
        screen.onNextCapture(() -> throttle.get().setRate(500));
        awaitTrue(() -> events.contains(FrameMonitor.Transition.STALL_END), "the stall to end");

        assertEquals(List.of(FrameMonitor.Transition.STALL_START, FrameMonitor.Transition.STALL_END), events);
        assertEquals(1, s.releaseAllSent(), "counted when the writer accepted RELEASE_ALL");
        awaitTrue(() -> c.releaseAllReceived() == 1, "the client to parse and accept RELEASE_ALL");
        assertTrue(device.awaitCall("keyRelease:17"), "the held key was really released");
        long framesAtRecovery = s.framesReceived();
        awaitTrue(() -> s.framesReceived() >= framesAtRecovery + 3, "frames to keep flowing");
        assertEquals(1, s.releaseAllSent(), "no second stall");
        assertFalse(lifecycle(s).isClosed());
        assertEquals(1, c.releaseAllReceived());
        assertFalse(c.lifecycle().isClosed());
    }

    // ---- timeouts --------------------------------------------------------------------------

    @Test
    void heartbeatAliveButNoFramesTimesOut() throws Exception {
        ServerCore core = core(normal());
        CountDownLatch gate = screen.blockCaptures(); // no frame is ever produced
        gates.add(gate);
        long startStamp = System.nanoTime();
        ClientSession c = startClient(core, normal());
        ServerSession s = serverSession(core);
        CloseRecorder rec = new CloseRecorder(lifecycle(s));
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        long handshakeMs = ms(System.nanoTime() - startStamp);

        rec.await();
        assertEquals("frame timeout", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        // 2 s after ACTIVE; ACTIVE happened after startStamp and before the client saw it
        assertWindow(ms(rec.decidedAtNanos - startStamp), 2000, 2000 + SLACK_MS + handshakeMs, "frame timeout decision");
        lifecycle(s).socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(ms(System.nanoTime() - rec.decidedAtNanos) <= GRACE.toMillis() + SLACK_MS);
        lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
        c.terminated().get(5, TimeUnit.SECONDS);
    }

    @Test
    void stuckImageWriteClosesSessionWithTimeout() throws Exception {
        Timeouts t = timeouts(Duration.ofSeconds(10)); // the server's frame timeout must not fire first
        ServerCore core = core(t);
        ClientSession c = startClient(core, t);
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        CloseRecorder rec = new CloseRecorder(c.lifecycle());
        long stamp = System.nanoTime(); // not later than the start of the write that will be stuck
        throttle.get().setRate(1);
        rec.await();
        assertEquals("image write stuck", rec.info.message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - stamp), IMG_WRITE_LIMIT.toMillis(),
                IMG_WRITE_LIMIT.toMillis() + 100 + SLACK_MS, "image write watchdog decision");
        c.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(ms(System.nanoTime() - rec.decidedAtNanos) <= GRACE.toMillis() + SLACK_MS);
        c.terminated().get(5, TimeUnit.SECONDS); // the throttled writer is released by the interrupt
        lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
    }

    @Test
    void screenGeometryChangeClosesWithScreenChanged() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        CloseRecorder rec = new CloseRecorder(c.lifecycle());
        long start = System.nanoTime();
        screen.setGeometry(1280, 720);
        rec.await();
        assertEquals(Packet.DisconnectReason.SCREEN_CHANGED, rec.info.reason());
        assertWindow(ms(rec.decidedAtNanos - start), 0, 1000 + 100 + SLACK_MS, "geometry change noticed within a second");
        c.terminated().get(5, TimeUnit.SECONDS);
        lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.SCREEN_CHANGED, lifecycle(s).closeInfo().orElseThrow().reason());
    }

    @Test
    void wrongRatioFrameIsProtocolError() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        CloseRecorder rec = new CloseRecorder(lifecycle(s));
        screen.setCaptureSize(1600, 1280); // 5:4 content while SCREEN_INFO said 16:9, and no geometry change
        rec.await();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, rec.info.reason());
        assertEquals("invalid frame", rec.info.message());
        lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
        c.terminated().get(5, TimeUnit.SECONDS);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Test
    void disconnectEndToEndReleasesInputAndSlot() throws Exception {
        ServerCore core = core(normal());
        ClientSession c = startClient(core, normal());
        c.activated().get(WAIT_S, TimeUnit.SECONDS);
        ServerSession s = serverSession(core);
        awaitControl(s);
        assertEquals(CommandWriter.OfferResult.ACCEPTED, s.offerInput(Packet.keyPress(17)));
        assertTrue(device.awaitCall("keyPress:17"));

        c.cancel();
        c.terminated().get(5, TimeUnit.SECONDS);
        lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the server slot to be freed");
        c.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(device.awaitCall("keyRelease:17"));
        awaitNoNewSessionThreads();
    }

    @Test
    void reconnectFiftyTimesReturnsToBaseline() throws Exception {
        ServerCore core = core(normal());
        int serverBaseline = core.tickerRegistrations();
        int clientBaseline = ticker.registrations();
        for (int i = 0; i < 50; i++) {
            ClientSession c = startClient(core, normal(), new FakeScreenSource(1920, 1080));
            c.activated().get(WAIT_S, TimeUnit.SECONDS);
            ServerSession s = serverSession(core);
            awaitControl(s);
            c.cancel();
            c.terminated().get(5, TimeUnit.SECONDS);
            lifecycle(s).terminated().get(5, TimeUnit.SECONDS);
            awaitTrue(() -> core.sessions().current().isEmpty(), "the server slot to be freed (round " + i + ")");
            c.inputReleased().get(WAIT_S, TimeUnit.SECONDS); // the executor is idle again for the next generation
            clients.remove(c); // already torn down
        }
        awaitNoNewSessionThreads();
        assertEquals(serverBaseline, core.tickerRegistrations());
        assertEquals(clientBaseline, ticker.registrations());
        assertTrue(core.sessions().current().isEmpty());
    }
}
