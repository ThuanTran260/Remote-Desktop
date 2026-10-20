package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.server.ServerSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A Server runtime and a Client runtime in one JVM over 127.0.0.1. Where a test must make the
 * Server see a stall, the Server runtime runs on a fake clock that the test moves forward; the
 * frozen clock does not hurt because the Client keeps the session alive with its own pings.
 */
@Timeout(120)
class AppRuntimeEndToEndTest {
    private static final Duration STALL = Duration.ofSeconds(3).plusMillis(1);

    private final List<AppRuntime> runtimes = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-pool");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
        pool.shutdownNow();
    }

    /** A started Server, a connected Client and the Server's first controllable frame. */
    private final class Rig {
        final AppRuntime server;
        final AppRuntime client;
        final FakeAdapterFactory adapters = new FakeAdapterFactory();

        Rig(LongSupplier serverClock) throws Exception {
            server = AppRuntime.forServer(serverConfig(serverClock, 0, 0));
            runtimes.add(server);
            get(server.startServer());
            Snapshot listening = server.snapshot();
            client = AppRuntime.forClient(clientConfig(listening.cmdPort(), listening.imgPort(), System::nanoTime),
                    adapters);
            runtimes.add(client);
            connect();
        }

        /** (Re)connects the Client and waits until the Server may send input. */
        Snapshot connect() {
            assertEquals(ConnectOutcome.STARTED, client.connect("127.0.0.1", server.snapshot().code()));
            return await(server, s -> s.controlEnabled());
        }

        /** Disconnects and waits until both sides are idle again. */
        void disconnect() throws Exception {
            ClientSession cs = client.clientSession().orElseThrow();
            assertTrue(client.disconnect());
            get(cs.terminated());
            get(cs.inputReleased());
            await(server, s -> s.phase() == UiPhase.LISTENING);
            await(client, s -> s.phase() == UiPhase.IDLE);
        }

        /** Parks the Client's screen capture and waits until the Server received every frame that was sent. */
        CountDownLatch stallFrames() throws Exception {
            FakeScreenSource screen = adapters.lastScreen();
            CountDownLatch unblock = screen.blockCaptures();
            assertTrue(screen.awaitBlocked(WAIT_S, TimeUnit.SECONDS));
            awaitTrue("every sent frame received", () -> server.snapshot().frameCount() == client.snapshot().frameCount());
            return unblock;
        }
    }

    // ---- input -----------------------------------------------------------------------------

    @Test
    void serverInputReachesClientDeviceAfterFirstFrame() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        Snapshot s = rig.server.snapshot();
        assertTrue(s.controlEnabled());
        assertEquals(SendResult.SENT, rig.server.sendInput(s.generation(), s.inputEpoch(), Packet.mousePress(100, 100, 1)));
        assertTrue(rig.adapters.device.awaitCall("mousePress:1"));
        assertEquals(List.of("mouseMove:100,100", "mousePress:1"), rig.adapters.device.log());
    }

    @Test
    void sendInputWithStaleGenerationIsRejected() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        long first = rig.server.snapshot().generation();
        rig.disconnect();
        Snapshot second = rig.connect();
        assertNotEquals(first, second.generation());

        assertEquals(SendResult.STALE_GENERATION, rig.server.sendInput(first, second.inputEpoch(), Packet.keyPress(65)));
        // FIFO on the wire: once 66 arrived, a leaked 65 would have arrived before it
        assertEquals(SendResult.SENT, rig.server.sendInput(second.generation(), second.inputEpoch(), Packet.keyPress(66)));
        assertTrue(rig.adapters.device.awaitCall("keyPress:66"));
        assertFalse(rig.adapters.device.log().contains("keyPress:65"));
    }

    @Test
    void sendInputWithStaleEpochAfterRecoveryIsRejected() throws Exception {
        FakeClock serverClock = new FakeClock();
        Rig rig = new Rig(serverClock);
        Snapshot before = rig.server.snapshot();
        long generation = before.generation();
        long oldEpoch = before.inputEpoch();

        CountDownLatch unblock = rig.stallFrames();
        serverClock.advance(STALL); // the Server's ticker decides STALL_START on its next tick
        Snapshot stalled = await(rig.server, s -> !s.controlEnabled() && s.inputEpoch() == oldEpoch + 1);
        assertEquals(SendResult.GATE_CLOSED, rig.server.sendInput(generation, stalled.inputEpoch(), Packet.keyPress(65)));

        unblock.countDown(); // the next frame ends the stall
        Snapshot recovered = await(rig.server, s -> s.controlEnabled());
        assertEquals(oldEpoch + 2, recovered.inputEpoch());
        assertEquals(SendResult.STALE_EPOCH, rig.server.sendInput(generation, oldEpoch, Packet.keyPress(65)));
        assertEquals(SendResult.SENT, rig.server.sendInput(generation, recovered.inputEpoch(), Packet.keyPress(66)));
        assertTrue(rig.adapters.device.awaitCall("keyPress:66"));
        assertFalse(rig.adapters.device.log().contains("keyPress:65"));
    }

    @Test
    void releaseControlInputReachesDeviceWhileFrameStalled() throws Exception {
        FakeClock serverClock = new FakeClock();
        Rig rig = new Rig(serverClock);
        Snapshot s = rig.server.snapshot();
        assertEquals(SendResult.SENT, rig.server.sendInput(s.generation(), s.inputEpoch(), Packet.keyPress(17)));
        FakeInputDevice device = rig.adapters.device;
        assertTrue(device.awaitCall("keyPress:17"));

        CountDownLatch unblock = rig.stallFrames();
        serverClock.advance(STALL);
        await(rig.server, x -> !x.controlEnabled());

        assertTrue(rig.server.releaseControlInput(s.generation()), "RELEASE_ALL goes out although the gate is closed");
        ClientSession cs = rig.client.clientSession().orElseThrow();
        awaitTrue("two RELEASE_ALL received", () -> cs.releaseAllReceived() >= 2);
        assertEquals(2, cs.releaseAllReceived(), "one for the stall, one for the lost focus");
        assertTrue(device.awaitCall("keyRelease:17"));
        assertEquals(1, device.log().stream().filter("keyRelease:17"::equals).count());
        assertEquals(1, rig.server.serverCore().sessions().current().orElseThrow().controlReleasesSent());
        assertFalse(rig.server.releaseControlInput(s.generation() + 1), "another generation is never released");
        unblock.countDown();
    }

    @Test
    void oldSessionObserverNotificationDoesNotChangeNewSnapshot() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        ServerSession old = rig.server.serverCore().sessions().current().orElseThrow();
        CountDownLatch inObserver = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gates.add(release);
        old.addFrameObserver((revision, transition) -> {
            inObserver.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // a stall decided for the old session; its notification is delivered outside the lock and stays parked
        long farFuture = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Future<?> late = pool.submit(() -> old.tick(farFuture));
        assertTrue(inObserver.await(WAIT_S, TimeUnit.SECONDS));

        rig.disconnect();
        Snapshot fresh = rig.connect();
        assertEquals(2, fresh.generation());
        assertEquals(0, fresh.inputEpoch());

        release.countDown();
        late.get(WAIT_S, TimeUnit.SECONDS);
        Snapshot after = rig.server.snapshot();
        assertEquals(2, after.generation());
        assertEquals(UiPhase.ACTIVE, after.phase());
        assertEquals(0, after.inputEpoch());
        assertTrue(after.controlEnabled());
    }

    // ---- shutdown (frame; budget and races are 4.1) ----------------------------------------------

    @Test
    void shutdownAsyncReturnsSameFutureWhenRepeated() throws Exception {
        AppRuntime rt = AppRuntime.forServer(serverConfig(System::nanoTime, 0, 0));
        runtimes.add(rt);
        get(rt.startServer());
        CompletableFuture<ShutdownReport> first = rt.shutdownAsync();
        CompletableFuture<ShutdownReport> second = rt.shutdownAsync();
        assertSame(first, second);
        ShutdownReport report = get(first);
        assertTrue(report.socketsClosed());
        assertTrue(report.workersStopped());
        assertEquals(InputRelease.NOT_NEEDED, report.inputRelease());
        assertSame(first, rt.shutdownAsync(), "also after completion");
    }

    @Test
    void shutdownSetsShuttingDownImmediately() throws Exception {
        // Client: the shutdown waits for a key release that the device holds back
        Rig rig = new Rig(System::nanoTime);
        Snapshot s = rig.server.snapshot();
        rig.server.sendInput(s.generation(), s.inputEpoch(), Packet.keyPress(17));
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));
        FakeInputDevice.Gate releaseGate = rig.adapters.device.blockOn("keyRelease:17");

        CompletableFuture<ShutdownReport> shutdown = rig.client.shutdownAsync();
        assertTrue(releaseGate.awaitEntered());
        assertFalse(shutdown.isDone());
        assertEquals(ConnectOutcome.SHUTTING_DOWN, rig.client.connect("127.0.0.1", "123456"));
        assertEquals(UiPhase.SHUTTING_DOWN, rig.client.snapshot().phase());
        releaseGate.open();
        assertEquals(InputRelease.RELEASED, get(shutdown).inputRelease());

        // Server: the shutdown arrives while startServer() is parked before the bind
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        gates.add(go);
        AppRuntime.Seams seams = new AppRuntime.Seams(com.ltm.rd.client.ClientHooks.none(),
                com.ltm.rd.client.SocketConnector.DEFAULT, UnaryOperator.identity(), () -> {
                    entered.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, () -> { });
        AppRuntime parked = AppRuntime.forServer(serverConfig(System::nanoTime, 0, 0), seams);
        runtimes.add(parked);
        CompletableFuture<Void> start = parked.startServer();
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS));
        CompletableFuture<ShutdownReport> parkedShutdown = parked.shutdownAsync();
        assertFalse(parkedShutdown.isDone());
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(parked.startServer()));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(UiPhase.SHUTTING_DOWN, parked.snapshot().phase());
        go.countDown();
        ExecutionException started = assertThrows(ExecutionException.class, () -> get(start));
        assertInstanceOf(IllegalStateException.class, started.getCause(), "the start was abandoned");
        assertTrue(get(parkedShutdown).socketsClosed());
    }

    @Test
    void shutdownClosesActiveSessionAndStopsInputExecutor() throws Exception {
        Set<Thread> before = liveRdThreads();
        Rig rig = new Rig(System::nanoTime);
        InputExecutor executor = rig.client.inputExecutor();
        Snapshot s = rig.server.snapshot();
        rig.server.sendInput(s.generation(), s.inputEpoch(), Packet.keyPress(17));
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));

        ShutdownReport report = get(rig.client.shutdownAsync());
        assertTrue(report.socketsClosed());
        assertTrue(report.workersStopped());
        assertEquals(InputRelease.RELEASED, report.inputRelease());
        assertEquals(InputExecutor.State.STOPPED, executor.state());
        assertTrue(rig.adapters.device.log().contains("keyRelease:17"));
        assertEquals(UiPhase.SHUTTING_DOWN, rig.client.snapshot().phase());

        get(rig.server.shutdownAsync());
        awaitTrue("no rd-* thread left but rd-shutdown", () -> {
            Set<Thread> left = liveRdThreads();
            left.removeAll(before);
            left.removeIf(t -> t.getName().equals("rd-shutdown"));
            return left.isEmpty();
        });
    }
}
