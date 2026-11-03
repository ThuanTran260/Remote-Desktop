package com.ltm.rd.host;

import com.ltm.rd.viewer.ViewerPhase;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.LifecyclePort;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class CommandReceiverTest {
    private static final int WAIT_S = 10;
    private static final Rectangle BOUNDS = new Rectangle(0, 0, 1920, 1080);

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
    private final List<FakeInputDevice.Gate> gates = new ArrayList<>();

    private FakeInputDevice device;
    private InputExecutor executor;

    @BeforeEach
    void setUp() {
        device = new FakeInputDevice();
        executor = newExecutor(64);
    }

    @AfterEach
    void tearDown() throws Exception {
        gates.forEach(FakeInputDevice.Gate::open);
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private InputExecutor newExecutor(int capacity) {
        return new InputExecutor(device, () -> BOUNDS, capacity, InputExecutor.defaultThreadFactory(), t -> {});
    }

    private SessionLifecycle<ViewerPhase> lifecycle(ViewerPhase phase) {
        SessionLifecycle<ViewerPhase> lc = new SessionLifecycle<>(ViewerPhase.CONNECTING, ViewerPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(100), scheduler, cleanup);
        lc.atomically(tx -> tx.advance(ViewerPhase.CONNECTING, phase));
        return lc;
    }

    private static void handle(SessionLifecycle<ViewerPhase> lc, CommandReceiver receiver, Packet p) {
        LifecyclePort<ViewerPhase> port = new LifecyclePort<>(lc, 1, null, System::nanoTime,
                new LifecyclePort.Shell<>() {
                    @Override public boolean isActive(ViewerPhase phase) { return phase == ViewerPhase.ACTIVE; }
                    @Override public boolean isActivationPending(ViewerPhase phase) { return phase == ViewerPhase.ATTACHING; }
                    @Override public boolean activationExpired(long now) { return false; }
                    @Override public CommandWriter writer() { return null; }
                    @Override public void tryActivate(SessionLifecycle.Tx<ViewerPhase> tx) { }
                });
        port.atomically(stx -> {
            receiver.handle(stx, p);
            return null;
        });
    }

    private CommandReceiver receiver(long generation) {
        return new CommandReceiver(executor, generation, BOUNDS);
    }

    @Test
    void pointerOutsideScreenIsProtocolError() {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ACTIVE);
        CommandReceiver r = receiver(1);
        handle(lc, r, Packet.mouseMove(1919, 1079)); // last valid pixel
        assertFalse(lc.isClosed());
        handle(lc, r, Packet.mouseMove(1920, 5));
        CloseInfo info = lc.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
    }

    @Test
    void controlBeforeActiveIsProtocolError() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ATTACHING);
        CommandReceiver r = receiver(1);
        handle(lc, r, Packet.keyPress(65));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lc.closeInfo().orElseThrow().reason());
        assertFalse(device.awaitCall("keyPress:65"), "nothing may reach the device before ACTIVE");
    }

    @Test
    void keyPressMapsToInputCommand() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ACTIVE);
        CommandReceiver r = receiver(1);
        handle(lc, r, Packet.keyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));
        handle(lc, r, Packet.mouseMove(10, 20));
        assertTrue(device.awaitCall("mouseMove:10,20"));
        handle(lc, r, Packet.wheel(-3));
        assertTrue(device.awaitCall("mouseWheel:-3"));
        handle(lc, r, Packet.keyRelease(65));
        assertTrue(device.awaitCall("keyRelease:65"));
        assertFalse(lc.isClosed());
    }

    @Test
    void releaseAllIsCountedWhenAccepted() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ACTIVE);
        CommandReceiver r = receiver(1);
        assertEquals(0, r.releaseAllReceived());
        handle(lc, r, Packet.keyPress(17));
        handle(lc, r, Packet.releaseAll());
        assertEquals(1, r.releaseAllReceived());
        assertTrue(device.awaitCall("keyRelease:17"), "RELEASE_ALL reaches the executor");

        SessionLifecycle<ViewerPhase> early = lifecycle(ViewerPhase.ATTACHING);
        CommandReceiver r2 = receiver(1);
        handle(early, r2, Packet.releaseAll());
        assertEquals(0, r2.releaseAllReceived(), "a rejected RELEASE_ALL is not counted");
    }

    @Test
    void overflowClosesSession() throws Exception {
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        executor = newExecutor(1);
        assertTrue(executor.beginSession(1));
        FakeInputDevice.Gate gate = device.blockOn("keyPress:1");
        gates.add(gate);
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ACTIVE);
        CommandReceiver r = receiver(1);
        handle(lc, r, Packet.keyPress(1));
        assertTrue(gate.awaitEntered(), "the worker is now blocked inside the device");
        handle(lc, r, Packet.keyPress(2)); // fills the one-slot deque
        assertFalse(lc.isClosed());
        handle(lc, r, Packet.keyPress(3));
        assertEquals(Packet.DisconnectReason.OVERFLOW, lc.closeInfo().orElseThrow().reason());
    }

    @Test
    void staleGenerationClosesSession() {
        SessionLifecycle<ViewerPhase> lc = lifecycle(ViewerPhase.ACTIVE);
        CommandReceiver r = receiver(7); // the executor never began generation 7
        handle(lc, r, Packet.keyPress(65));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lc.closeInfo().orElseThrow().reason());
    }
}
