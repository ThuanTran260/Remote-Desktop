package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
 * Mouse events on a real RemotePanel and InputCapture, through a Server runtime and a Client
 * runtime over 127.0.0.1, to a FakeInputDevice. Where a test needs a stall, the Server runs on a
 * fake clock that the test moves forward.
 */
@Timeout(120)
class MouseEndToEndIntegrationTest {
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

        /** (Re)connects the Client and applies the first controllable snapshot to the panel. */
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

        void edt(Runnable r) throws Exception {
            SwingUtilities.invokeAndWait(r);
        }

        void press(int x, int y, int b) throws Exception {
            edt(() -> capture.mousePressed(event(MouseEvent.MOUSE_PRESSED, x, y, b, mask(b))));
        }

        void drag(int x, int y, int b) throws Exception {
            edt(() -> capture.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, x, y, 0, mask(b))));
        }

        void release(int x, int y, int b) throws Exception {
            edt(() -> capture.mouseReleased(event(MouseEvent.MOUSE_RELEASED, x, y, b, 0)));
        }

        void move(int x, int y) throws Exception {
            edt(() -> capture.mouseMoved(event(MouseEvent.MOUSE_MOVED, x, y, 0, 0)));
        }

        MouseEvent event(int id, int x, int y, int b, int mods) {
            return new MouseEvent(panel, id, 0, mods, x, y, 1, false, b);
        }

        /** Parks the Client's capture and waits until the Server received every frame that was sent. */
        CountDownLatch stallFrames() throws Exception {
            FakeScreenSource screen = adapters.lastScreen();
            CountDownLatch unblock = screen.blockCaptures();
            assertTrue(screen.awaitBlocked(WAIT_S, TimeUnit.SECONDS));
            awaitTrue("every sent frame received", () -> server.snapshot().frameCount() == client.snapshot().frameCount());
            return unblock;
        }
    }

    private static int mask(int button) {
        return button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
    }

    @Test
    void clickDragReleaseReachesDeviceInOrder() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        rig.press(480, 270, MouseEvent.BUTTON1);
        rig.drag(490, 280, MouseEvent.BUTTON1);
        rig.release(490, 280, MouseEvent.BUTTON1);
        assertTrue(rig.adapters.device.awaitCall("mouseRelease:1"));
        assertEquals(List.of("mouseMove:960,540", "mousePress:1", "mouseMove:980,560", "mouseMove:980,560",
                "mouseRelease:1"), rig.adapters.device.log());
    }

    @Test
    void dragOutsideThenReleaseReachesDevice() throws Exception {
        Rig rig = new Rig(System::nanoTime);
        rig.press(480, 270, MouseEvent.BUTTON1);
        rig.drag(-50, 600, MouseEvent.BUTTON1); // far outside: clamped to the bottom-left corner
        rig.release(-50, 600, MouseEvent.BUTTON1);
        assertTrue(rig.adapters.device.awaitCall("mouseRelease:1"));
        List<String> log = rig.adapters.device.log();
        assertEquals("mouseMove:0,1079", log.get(log.size() - 2));
        assertEquals("mouseRelease:1", log.get(log.size() - 1));
    }

    @Test
    void lateReleaseAfterEpochChangeIsDropped() throws Exception {
        FakeClock serverClock = new FakeClock();
        Rig rig = new Rig(serverClock);
        Snapshot before = rig.refresh();
        rig.press(480, 270, MouseEvent.BUTTON1);
        assertTrue(rig.adapters.device.awaitCall("mousePress:1"));

        CountDownLatch unblock = rig.stallFrames();
        serverClock.advance(STALL);
        Snapshot stalled = await(rig.server, s -> s.inputEpoch() == before.inputEpoch() + 1);
        assertTrue(rig.adapters.device.awaitCall("mouseRelease:1"), "RELEASE_ALL of the stall released the button");
        rig.refresh(); // the UI sees the new epoch: held state is cleared
        assertEquals(stalled.inputEpoch(), rig.server.snapshot().inputEpoch());
        int sentBefore = rig.sink.sent().size();
        rig.release(480, 270, MouseEvent.BUTTON1); // the user lets go late
        assertEquals(sentBefore, rig.sink.sent().size(), "the late release never reaches the server");

        unblock.countDown();
        await(rig.server, s -> s.controlEnabled());
        rig.refresh();
        rig.move(480, 270); // FIFO: a leaked release would arrive before this move
        awaitTrue("move delivered", () -> rig.adapters.device.log().stream().filter("mouseMove:960,540"::equals).count() >= 2);
        assertEquals(1, rig.adapters.device.log().stream().filter("mouseRelease:1"::equals).count());
    }

    @Test
    void imageStallClosesGateForMouse() throws Exception {
        FakeClock serverClock = new FakeClock();
        Rig rig = new Rig(serverClock);
        CountDownLatch unblock = rig.stallFrames();
        serverClock.advance(STALL);
        await(rig.server, s -> !s.controlEnabled());

        // the panel still shows the old epoch, so the press is sent and the Server refuses it
        rig.press(480, 270, MouseEvent.BUTTON1);
        FakeController.Sent refused = rig.sink.sent().get(rig.sink.sent().size() - 1);
        assertEquals(SendResult.GATE_CLOSED, refused.result());
        assertFalse(rig.adapters.device.log().contains("mousePress:1"));

        unblock.countDown();
        await(rig.server, s -> s.controlEnabled());
        rig.refresh();
        rig.press(480, 270, MouseEvent.BUTTON1);
        assertEquals(SendResult.SENT, rig.sink.sent().get(rig.sink.sent().size() - 1).result());
        assertTrue(rig.adapters.device.awaitCall("mousePress:1"));
    }

    @Test
    void queueOverflowClosesSessionWithOverflow() throws Exception {
        Rig rig = new Rig(System::nanoTime);
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
    }
}
