package com.ltm.rd.server;

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
    private final SessionLifecycle<ServerPhase> lifecycle = new SessionLifecycle<>(ServerPhase.RESERVED,
            ServerPhase.CLOSING, () -> 100L, Duration.ZERO, scheduler, Runnable::run,
            body -> new Thread(body) {
                @Override public synchronized void start() { started.add(getName()); super.start(); }
            });
    private int activations;
    private final LifecyclePort<ServerPhase> port = new LifecyclePort<>(lifecycle, 7, null, () -> 100L,
            new LifecyclePort.Shell<>() {
                @Override public boolean isActive(ServerPhase p) { return p == ServerPhase.ACTIVE; }
                @Override public boolean isActivationPending(ServerPhase p) { return p == ServerPhase.RESERVED; }
                @Override public boolean activationExpired(long now) { return false; }
                @Override public CommandWriter writer() { return null; }
                @Override public void tryActivate(SessionLifecycle.Tx<ServerPhase> tx) { activations++; }
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
                assertTrue(raw.advance(ServerPhase.RESERVED, ServerPhase.ACTIVE));
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
}
