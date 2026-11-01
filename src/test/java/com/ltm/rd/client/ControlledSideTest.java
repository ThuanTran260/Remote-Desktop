package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.LifecyclePort;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ControlledSideTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SessionLifecycle<ClientPhase> lifecycle = new SessionLifecycle<>(ClientPhase.ATTACHING,
            ClientPhase.CLOSING, System::nanoTime, Duration.ZERO, scheduler, Runnable::run);
    private final CountDownLatch flushed = new CountDownLatch(1);
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
        @Override public void flush() { flushed.countDown(); }
    };
    private final CommandWriter writer = new CommandWriter(new DataOutputStream(bytes), e -> {});
    private final FakeInputDevice device = new FakeInputDevice();
    private final FakeScreenSource screen = new FakeScreenSource(1920, 1080);
    private final InputExecutor input = new InputExecutor(device, () -> new Rectangle(0, 0, 1920, 1080), 64,
            InputExecutor.defaultThreadFactory(), e -> {});
    private final LifecyclePort<ClientPhase> port = new LifecyclePort<>(lifecycle, 7, null, System::nanoTime,
            new LifecyclePort.Shell<>() {
                @Override public boolean isActive(ClientPhase phase) { return phase == ClientPhase.ACTIVE; }
                @Override public boolean isActivationPending(ClientPhase phase) { return phase == ClientPhase.ATTACHING; }
                @Override public boolean activationExpired(long now) { return false; }
                @Override public CommandWriter writer() { return writer; }
                @Override public void tryActivate(SessionLifecycle.Tx<ClientPhase> tx) { }
            });
    private FakeInputDevice.Gate gate;

    private ControlledSide side(Runnable beforeSenderStart) {
        return new ControlledSide(port, input, screen, Timeouts.DEFAULTS, System::nanoTime, beforeSenderStart);
    }

    private static Channel imageChannel() {
        return new Channel(null, null, new DataOutputStream(new ByteArrayOutputStream()));
    }

    private void close() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
    }

    @AfterEach void cleanup() throws Exception {
        if (gate != null) gate.open();
        close();
        input.shutdownAsync().get(5, TimeUnit.SECONDS);
        writer.stop();
        scheduler.shutdownNow();
    }

    @Test void onAuthenticatedOffersScreenInfoOfAnnouncedGeometry() throws Exception {
        ControlledSide side = side(this::close);
        assertFalse(side.readyForActive());
        side.announce(screen.geometry());
        assertTrue(side.readyForActive());
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        writer.start();
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
        Packet offered = PacketIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                Protocol.CMD_MAX_LEN, EnumSet.of(PacketType.SCREEN_INFO)).orElseThrow();
        assertEquals(Packet.screenInfo(1920, 1080), offered);
    }

    @Test void inputBeforeActiveKeepsV1Reason() {
        ControlledSide side = side(this::close);
        side.announce(screen.geometry());
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        port.atomically(stx -> { side.onPacket(stx, Packet.keyPress(65)); return null; });
        CloseInfo info = lifecycle.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("input before session active", info.message());
        assertTrue(device.log().isEmpty());
    }

    @Test void refusedBeginReturnsFalseAndStartsNothing() {
        assertTrue(input.beginSession(8));
        AtomicInteger hooks = new AtomicInteger();
        ControlledSide side = side(hooks::incrementAndGet);
        side.announce(screen.geometry());
        boolean accepted = port.atomically(stx -> side.onActive(stx, 0, imageChannel()));
        assertFalse(accepted);
        assertEquals(0, side.inputBeginCount());
        assertEquals(0, side.senderRuns());
        assertEquals(0, lifecycle.liveWorkers());
        assertEquals(0, hooks.get());
        assertTrue(side.onClosing().isDone());
        assertEquals(InputExecutor.State.OPEN, input.state());
    }

    @Test void onActiveBeginsOnceAndDefersTheSender() throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        ControlledSide side = side(() -> { hooks.incrementAndGet(); close(); });
        side.announce(screen.geometry());
        lifecycle.atomically(tx -> {
            boolean accepted = port.atomically(stx -> side.onActive(stx, 0, imageChannel()));
            assertTrue(accepted);
            assertEquals(1, side.inputBeginCount());
            assertEquals(0, hooks.get());
            assertEquals(0, side.senderRuns());
            assertEquals(0, lifecycle.liveWorkers());
            assertTrue(tx.advance(ClientPhase.ATTACHING, ClientPhase.ACTIVE));
            return null;
        });
        assertEquals(1, hooks.get());
        assertEquals(1, side.inputBeginCount());
        assertEquals(0, side.senderRuns());
        assertEquals(0, side.framesSent());
        side.onClosing().get(5, TimeUnit.SECONDS);
    }

    @Test void onClosingAfterBeginWaitsForEndSession() throws Exception {
        ControlledSide side = side(this::close);
        side.announce(screen.geometry());
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        assertEquals(0, side.releaseAllReceived());
        assertEquals(0, side.framesSent());
        port.atomically(stx -> side.onActive(stx, 0, imageChannel()));
        assertEquals(SubmitResult.ACCEPTED, input.submit(7, new InputCommand.KeyPress(17)));
        assertTrue(device.awaitCall("keyPress:17"));
        gate = device.blockOn("keyRelease:17");
        CompletableFuture<Void> released = side.onClosing();
        assertTrue(gate.awaitEntered());
        assertFalse(released.isDone());
        gate.open();
        released.get(5, TimeUnit.SECONDS);
        assertEquals(InputExecutor.State.IDLE, input.state());
    }

    @Test void activePacketsReachInputAndCountReleaseAll() throws Exception {
        ControlledSide side = side(this::close);
        assertEquals(0, side.releaseAllReceived());
        side.announce(screen.geometry());
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        assertTrue(input.beginSession(7));
        lifecycle.atomically(tx -> tx.advance(ClientPhase.ATTACHING, ClientPhase.ACTIVE));
        port.atomically(stx -> { side.onPacket(stx, Packet.keyPress(17)); return null; });
        assertTrue(device.awaitCall("keyPress:17"));
        port.atomically(stx -> { side.onPacket(stx, Packet.releaseAll()); return null; });
        assertEquals(1, side.releaseAllReceived());
        assertTrue(device.awaitCall("keyRelease:17"));
    }
}
