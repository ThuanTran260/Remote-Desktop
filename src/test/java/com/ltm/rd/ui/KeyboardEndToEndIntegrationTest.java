package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.server.ServerSession;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Keys and focus loss on a real RemotePanel and InputCapture, through a Server runtime and a Client
 * runtime over 127.0.0.1, to a FakeInputDevice. Device logs prove what was released; the protocol
 * counters (controlReleasesSent, releaseAllReceived) prove how many RELEASE_ALL went over the wire.
 */
@Timeout(120)
class KeyboardEndToEndIntegrationTest {
    private static final Duration STALL = Duration.ofSeconds(3).plusMillis(1);

    private final List<AppRuntime> runtimes = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private Set<Thread> baseline;

    @BeforeEach
    void captureThreads() {
        baseline = liveRdThreads();
    }

    @AfterEach
    void cleanup() throws Exception {
        cleanups.forEach(Runnable::run);
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
        awaitNoNewRdThreads(baseline);
    }

    private final class Rig {
        final AppRuntime server;
        final AppRuntime client;
        final FakeAdapterFactory adapters = new FakeAdapterFactory();
        final FakeController sink;
        final RemotePanel panel = new RemotePanel();
        final InputCapture capture;

        Rig(LongSupplier serverClock) throws Exception {
            server = AppRuntime.forServer(serverConfig(serverClock, 0, 0));
            runtimes.add(server);
            get(server.startServer());
            Snapshot listening = server.snapshot();
            client = AppRuntime.forClient(clientConfig(listening.cmdPort(), listening.imgPort(), System::nanoTime),
                    adapters);
            runtimes.add(client);
            sink = new FakeController(server);
            panel.setSize(960, 540);
            capture = new InputCapture(sink, panel, () -> true, () -> { });
            connect();
        }

        void connect() throws Exception {
            assertEquals(ConnectOutcome.STARTED, client.connect("127.0.0.1", server.snapshot().code()));
            await(server, s -> s.controlEnabled());
            refresh();
        }

        /** What the snapshot timer does: apply the newest snapshot, then tell the capture. */
        Snapshot refresh() throws Exception {
            Snapshot s = server.snapshot();
            SwingUtilities.invokeAndWait(() -> {
                panel.apply(s);
                capture.onApplied(s.generation(), s.inputEpoch());
            });
            return s;
        }

        void key(int id, int code) throws Exception {
            KeyEvent e = new KeyEvent(panel, id, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
            SwingUtilities.invokeAndWait(() -> {
                if (id == KeyEvent.KEY_PRESSED) capture.keyPressed(e);
                else capture.keyReleased(e);
            });
        }

        void focusLost() throws Exception {
            SwingUtilities.invokeAndWait(() -> capture.focusLost(new FocusEvent(panel, FocusEvent.FOCUS_LOST)));
        }

        void focusGained() throws Exception {
            SwingUtilities.invokeAndWait(() -> capture.focusGained(new FocusEvent(panel, FocusEvent.FOCUS_GAINED)));
        }

        ClientSession clientSession() {
            return client.clientSession().orElseThrow();
        }

        ServerSession serverSession() {
            return server.serverCore().sessions().current().orElseThrow();
        }

        long count(String call) {
            return adapters.device.log().stream().filter(call::equals).count();
        }

        /** FIFO sentinel: once this move reached the device, everything sent before it was executed. */
        void sentinel() throws Exception {
            Snapshot s = server.snapshot();
            assertEquals(SendResult.SENT, server.sendInput(s.generation(), s.inputEpoch(), Packet.mouseMove(5, 6)));
            assertTrue(adapters.device.awaitCall("mouseMove:5,6"));
        }

        CountDownLatch stallFrames() throws Exception {
            FakeScreenSource screen = adapters.lastScreen();
            CountDownLatch unblock = screen.blockCaptures();
            assertTrue(screen.awaitBlocked(WAIT_S, TimeUnit.SECONDS));
            awaitTrue("every sent frame received", () -> server.snapshot().frameCount() == client.snapshot().frameCount());
            return unblock;
        }
    }

    @Test
    void modifierHeldThenFocusLossReleasesOnDevice() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        rig.key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL);
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));
        rig.focusLost();
        assertTrue(rig.adapters.device.awaitCall("keyRelease:17"));
        assertEquals(1, rig.count("keyRelease:17"));
        assertEquals(1, rig.serverSession().controlReleasesSent());
        awaitTrue("RELEASE_ALL received", () -> rig.clientSession().releaseAllReceived() == 1);
    }

    @Test
    void networkDropWhileCtrlHeldReleasesOnClient() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        ClientSession first = rig.clientSession();
        rig.key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL);
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));

        assertTrue(rig.server.disconnect()); // the session ends without any key release from the user
        assertTrue(rig.adapters.device.awaitCall("keyRelease:17"));
        get(first.inputReleased());
        await(rig.server, s -> s.phase() == UiPhase.LISTENING);
        await(rig.client, s -> s.phase() == UiPhase.IDLE);

        rig.connect(); // the next session does not hold the old key
        rig.focusLost();
        awaitTrue("RELEASE_ALL of the new session", () -> rig.clientSession().releaseAllReceived() == 1);
        rig.sentinel();
        assertEquals(1, rig.count("keyRelease:17"), "nothing was held again");
    }

    @Test
    void releaseLateAfterGateClosedDoesNotExecuteOldEpoch() throws Exception {
        FakeClock serverClock = new FakeClock();
        Rig rig = new Rig(serverClock);
        Snapshot before = rig.refresh();
        rig.key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL);
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));

        CountDownLatch unblock = rig.stallFrames();
        serverClock.advance(STALL);
        await(rig.server, s -> s.inputEpoch() == before.inputEpoch() + 1);
        assertTrue(rig.adapters.device.awaitCall("keyRelease:17"), "RELEASE_ALL of the stall released the key");

        // the UI has not seen the stall yet: the late release still carries the old epoch
        rig.key(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL);
        FakeController.Sent late = rig.sink.sent().get(rig.sink.sent().size() - 1);
        assertEquals("keyRelease 17", FakeController.describe(late.packet()));
        assertTrue(late.result() == SendResult.GATE_CLOSED || late.result() == SendResult.STALE_EPOCH,
                "was " + late.result());

        unblock.countDown();
        await(rig.server, s -> s.controlEnabled());
        rig.refresh();
        rig.sentinel();
        assertEquals(1, rig.count("keyRelease:17"), "the device released exactly once");
    }

    @Test
    void queueOverflowWhileKeyHeldStillReleasesKey() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        ClientSession cs = rig.clientSession();
        rig.key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL);
        assertTrue(rig.adapters.device.awaitCall("keyPress:17"));
        FakeInputDevice.Gate gate = rig.adapters.device.blockOn("mouseWheel:1");
        cleanups.add(gate::open);

        Snapshot s = rig.refresh();
        SendResult last = SendResult.SENT;
        for (int i = 0; i < 5000 && last == SendResult.SENT; i++) {
            last = rig.server.sendInput(s.generation(), s.inputEpoch(), Packet.wheel(1));
        }
        assertNotEquals(SendResult.SENT, last, "the queues filled up while the device was stuck");
        await(rig.server, x -> x.message().equals(Messages.forClose(Packet.DisconnectReason.OVERFLOW)));

        gate.open();
        get(cs.inputReleased());
        assertEquals(1, rig.count("keyRelease:17"), "the end of the generation released the key");
    }

    @Test
    void releaseAllCountersAgreeAcrossEnds() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        rig.focusLost();
        rig.focusGained();
        rig.focusLost();
        assertEquals(2, rig.serverSession().controlReleasesSent());
        awaitTrue("both RELEASE_ALL received", () -> rig.clientSession().releaseAllReceived() == 2);
        assertEquals(2, rig.clientSession().releaseAllReceived());
    }
}
