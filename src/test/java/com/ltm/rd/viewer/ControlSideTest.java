package com.ltm.rd.viewer;

import com.ltm.rd.host.HostPhase;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.LifecyclePort;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ControlSideTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> started = new CopyOnWriteArrayList<>();
    private final SessionLifecycle<HostPhase> lifecycle = new SessionLifecycle<>(HostPhase.RESERVED,
            HostPhase.CLOSING, () -> 100L, Duration.ZERO, scheduler, Runnable::run,
            body -> new Thread(body) {
                @Override public synchronized void start() { started.add(getName()); super.start(); }
            });
    private int activations;
    private final LifecyclePort<HostPhase> port = new LifecyclePort<>(lifecycle, 7, null, () -> 100L,
            new LifecyclePort.Shell<>() {
                @Override public boolean isActive(HostPhase p) { return p == HostPhase.ACTIVE; }
                @Override public boolean isActivationPending(HostPhase p) { return p == HostPhase.RESERVED; }
                @Override public boolean activationExpired(long now) { return false; }
                @Override public CommandWriter writer() { return null; }
                @Override public void tryActivate(SessionLifecycle.Tx<HostPhase> tx) { activations++; }
            });
    private final ControlSide side = new ControlSide(port, Timeouts.DEFAULTS, () -> 100L, false);

    @AfterEach void cleanup() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
        scheduler.shutdownNow();
    }

    @Test void readyOnlyAfterFirstScreenInfo() {
        assertFalse(side.readyForActive());
        assertTrue(side.screen().isEmpty());
        port.atomically(tx -> {
            side.onAuthenticated(tx);
            assertFalse(side.readyForActive());
            side.onPacket(tx, Packet.screenInfo(1920, 1080));
            return null;
        });
        assertTrue(side.readyForActive());
        assertEquals(new Packet.SizePayload(1920, 1080), side.screen().orElseThrow());
        assertEquals(1, activations);
    }

    @Test void secondScreenInfoClosesWithV1Reason() {
        port.atomically(tx -> {
            side.onPacket(tx, Packet.screenInfo(1920, 1080));
            side.onPacket(tx, Packet.screenInfo(1920, 1080));
            return null;
        });
        assertEquals(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected SCREEN_INFO", true),
                lifecycle.closeInfo().orElseThrow());
        assertEquals(1, activations);
    }

    @Test void unexpectedPacketClosesWithV1Reason() {
        port.atomically(tx -> { side.onPacket(tx, Packet.ping(1)); return null; });
        assertEquals(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected PING", true),
                lifecycle.closeInfo().orElseThrow());
    }

    @Test void onActiveDefersTheFrameReceiverStart() {
        ControlSide receiving = new ControlSide(port, Timeouts.DEFAULTS, () -> 100L, true);
        Channel image = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        lifecycle.atomically(raw -> {
            port.atomically(tx -> {
                receiving.onPacket(tx, Packet.screenInfo(1920, 1080));
                assertTrue(raw.advance(HostPhase.RESERVED, HostPhase.ACTIVE));
                assertTrue(receiving.onActive(tx, 100L, image));
                return null;
            });
            assertEquals(0, lifecycle.liveWorkers());
            assertTrue(started.isEmpty());
            return null;
        });
        assertEquals(List.of("rd-frame-receiver"), started);
        assertEquals(100L, receiving.activeAtNanos().orElseThrow());
    }

    @Test void tickBeforeActiveReturnsFalse() {
        assertFalse(side.tick(100));
        assertFalse(lifecycle.isClosed());
        assertTrue(side.activeAtNanos().isEmpty());
    }

    @Test void onClosingIsAlreadyComplete() {
        assertTrue(side.onClosing().isDone());
        assertNull(side.onClosing().join());
    }

    @Test void closedWriterWithOpenGateReturnsGateClosed() {
        CommandWriter closedWriter = new CommandWriter(new DataOutputStream(OutputStream.nullOutputStream()), e -> {});
        closedWriter.stop();
        LifecyclePort<HostPhase> actualPort = new LifecyclePort<>(lifecycle, 7, null, () -> 100L,
                new LifecyclePort.Shell<>() {
                    @Override public boolean isActive(HostPhase phase) { return phase == HostPhase.ACTIVE; }
                    @Override public boolean isActivationPending(HostPhase phase) { return phase == HostPhase.RESERVED; }
                    @Override public boolean activationExpired(long now) { return false; }
                    @Override public CommandWriter writer() { return closedWriter; }
                    @Override public void tryActivate(SessionLifecycle.Tx<HostPhase> tx) { }
                });
        ControlSide active = new ControlSide(actualPort, Timeouts.DEFAULTS, () -> 100L, false);
        Channel image = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        actualPort.atomically(tx -> {
            active.onPacket(tx, Packet.screenInfo(8, 8));
            lifecycle.atomically(raw -> raw.advance(HostPhase.RESERVED, HostPhase.ACTIVE));
            assertTrue(active.onActive(tx, 100L, image));
            return null;
        });
        active.publishFrame(7, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), 100L);
        assertTrue(active.controlEnabled());
        long epoch = active.inputEpoch();
        assertEquals(InputOutcome.GATE_CLOSED, active.offerInput(epoch, Packet.keyPress(65)));
        assertEquals(epoch, active.inputEpoch());
        assertTrue(active.controlEnabled());
        assertFalse(lifecycle.isClosed());
        assertTrue(com.ltm.rd.common.SessionCloseProbe.queued(closedWriter).isEmpty());
    }

    @Test void activeTickReportsHandledAcrossStallAndTimeout() {
        CommandWriter command = new CommandWriter(new DataOutputStream(OutputStream.nullOutputStream()), e -> {});
        try {
            LifecyclePort<HostPhase> actualPort = new LifecyclePort<>(lifecycle, 7, null, () -> 100L,
                    new LifecyclePort.Shell<>() {
                        @Override public boolean isActive(HostPhase phase) { return phase == HostPhase.ACTIVE; }
                        @Override public boolean isActivationPending(HostPhase phase) { return phase == HostPhase.RESERVED; }
                        @Override public boolean activationExpired(long now) { return false; }
                        @Override public CommandWriter writer() { return command; }
                        @Override public void tryActivate(SessionLifecycle.Tx<HostPhase> tx) { }
                    });
            ControlSide active = new ControlSide(actualPort, Timeouts.DEFAULTS, () -> 100L, false);
            List<FrameMonitor.Transition> changes = new ArrayList<>();
            active.addFrameObserver((revision, transition) -> changes.add(transition));
            assertFalse(active.tick(100L));
            Channel image = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                    new DataOutputStream(OutputStream.nullOutputStream()));
            actualPort.atomically(tx -> {
                active.onPacket(tx, Packet.screenInfo(8, 8));
                lifecycle.atomically(raw -> raw.advance(HostPhase.RESERVED, HostPhase.ACTIVE));
                assertTrue(active.onActive(tx, 100L, image));
                return null;
            });
            active.publishFrame(7, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), 100L);
            assertTrue(active.tick(100L));
            assertTrue(active.tick(100L + 2_999_999_999L));
            assertTrue(active.controlEnabled());
            assertTrue(active.tick(100L + 3_000_000_000L));
            assertFalse(active.controlEnabled());
            assertEquals(1, active.releaseAllSent());
            assertTrue(active.tick(100L + 3_000_000_000L));
            assertEquals(1, active.releaseAllSent());
            assertEquals(List.of(Packet.releaseAll()), com.ltm.rd.common.SessionCloseProbe.queued(command));
            assertTrue(active.tick(100L + 15_000_000_000L));
            assertEquals(List.of(FrameMonitor.Transition.STALL_START, FrameMonitor.Transition.TIMEOUT), changes);
            assertEquals("frame timeout", lifecycle.closeInfo().orElseThrow().message());
        } finally {
            command.stop();
        }
    }
}
