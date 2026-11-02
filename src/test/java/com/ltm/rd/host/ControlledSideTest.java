package com.ltm.rd.host;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientPhase;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class ControlledSideTest {
    private CountDownLatch workerGate;
    private final CountDownLatch workerEntered = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SessionLifecycle<ClientPhase> lifecycle = new SessionLifecycle<>(ClientPhase.ATTACHING,
            ClientPhase.CLOSING, System::nanoTime, Duration.ZERO, scheduler, Runnable::run,
            body -> new Thread(() -> {
                if (workerGate != null && Thread.currentThread().getName().equals("rd-host-prepare")) {
                    workerEntered.countDown();
                    awaitUninterruptibly(workerGate);
                }
                body.run();
            }));
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
                @Override public boolean activationExpired(long now) { return expired.get(); }
                @Override public CommandWriter writer() { return writer; }
                @Override public void tryActivate(SessionLifecycle.Tx<ClientPhase> tx) { prepared.countDown(); }
            });
    private FakeInputDevice.Gate gate;
    private final CountDownLatch prepared = new CountDownLatch(1);
    private final AtomicBoolean expired = new AtomicBoolean();
    private final AtomicInteger boundsPublished = new AtomicInteger();
    private final List<String> adapterCalls = new CopyOnWriteArrayList<>();
    private final CountDownLatch adapterEntered = new CountDownLatch(1);
    private CountDownLatch adapterGate;
    private RuntimeException adapterFailure;
    private final AdapterFactory adapters = new AdapterFactory() {
        @Override public InputDevice inputDevice() { throw new AssertionError("session must borrow input"); }
        @Override public void refreshInputDevice() {
            adapterCalls.add("refresh:" + Thread.currentThread().getName());
        }
        @Override public ScreenSource newScreenSource() {
            adapterCalls.add("screen:" + Thread.currentThread().getName());
            adapterEntered.countDown();
            if (adapterGate != null) awaitUninterruptibly(adapterGate);
            if (adapterFailure != null) throw adapterFailure;
            return screen;
        }
    };

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { latch.await(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void authenticate(ControlledSide side) throws Exception {
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        assertTrue(prepared.await(5, TimeUnit.SECONDS));
        assertTrue(side.readyForActive());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lifecycle.liveWorkers() != 0) {
            assertTrue(System.nanoTime() < deadline);
            Thread.onSpinWait();
        }
    }

    private ControlledSide side(Runnable beforeSenderStart) {
        return new ControlledSide(port, input, adapters, geometry -> boundsPublished.incrementAndGet(),
                Timeouts.DEFAULTS, System::nanoTime, beforeSenderStart);
    }

    private static Channel imageChannel() {
        return new Channel(null, null, new DataOutputStream(new ByteArrayOutputStream()));
    }

    private void close() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
    }

    @AfterEach void cleanup() throws Exception {
        if (gate != null) gate.open();
        if (adapterGate != null) adapterGate.countDown();
        if (workerGate != null) workerGate.countDown();
        close();
        lifecycle.terminated().get(5, TimeUnit.SECONDS);
        input.shutdownAsync().get(5, TimeUnit.SECONDS);
        writer.stop();
        scheduler.shutdownNow();
    }

    @Test void prepareOffersScreenInfoOnlyAfterAccepted() throws Exception {
        ControlledSide side = side(this::close);
        assertFalse(side.readyForActive());
        authenticate(side);
        assertTrue(side.readyForActive());
        writer.start();
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
        Packet offered = PacketIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                Protocol.CMD_MAX_LEN, EnumSet.of(PacketType.SCREEN_INFO)).orElseThrow();
        assertEquals(Packet.screenInfo(1920, 1080), offered);
    }

    @Test void screenInfoOfferClosedNeverPrepares() throws Exception {
        writer.stop();
        ControlledSide side = side(this::close);
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        lifecycle.socketsClosed().get(5, TimeUnit.SECONDS);
        assertFalse(side.readyForActive());
        assertEquals(0, side.inputBeginCount());
        assertEquals(0, side.senderRuns());
        assertTrue(lifecycle.isClosed());
    }

    @Test void authenticatedDefersPreparationUntilAfterTransaction() {
        ControlledSide side = side(this::close);
        port.atomically(stx -> {
            side.onAuthenticated(stx);
            assertFalse(side.readyForActive());
            assertEquals(0, lifecycle.liveWorkers());
            return null;
        });
    }

    @Test void inputBeforeActiveKeepsV1Reason() throws Exception {
        ControlledSide side = side(this::close);
        authenticate(side);
        port.atomically(stx -> { side.onPacket(stx, Packet.keyPress(65)); return null; });
        CloseInfo info = lifecycle.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("input before session active", info.message());
        assertTrue(device.log().isEmpty());
    }

    @Test void refusedBeginReturnsFalseAndStartsNothing() throws Exception {
        assertTrue(input.beginSession(8));
        AtomicInteger hooks = new AtomicInteger();
        ControlledSide side = side(hooks::incrementAndGet);
        authenticate(side);
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
        authenticate(side);
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
        authenticate(side);
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
        authenticate(side);
        assertTrue(input.beginSession(7));
        lifecycle.atomically(tx -> tx.advance(ClientPhase.ATTACHING, ClientPhase.ACTIVE));
        port.atomically(stx -> { side.onPacket(stx, Packet.keyPress(17)); return null; });
        assertTrue(device.awaitCall("keyPress:17"));
        port.atomically(stx -> { side.onPacket(stx, Packet.releaseAll()); return null; });
        assertEquals(1, side.releaseAllReceived());
        assertTrue(device.awaitCall("keyRelease:17"));
    }

    @Test void screenInfoOfferOverflowNeverBegins() throws Exception {
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) {
            assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(i)));
        }
        ControlledSide side = side(this::close);
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        lifecycle.socketsClosed().get(5, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.OVERFLOW, lifecycle.closeInfo().orElseThrow().reason());
        assertFalse(side.readyForActive());
        assertTrue(side.screen().isEmpty());
        assertEquals(0, side.inputBeginCount());
        assertEquals(0, side.senderRuns());
        assertEquals(0, boundsPublished.get());
    }

    @Test void prepareUsesWorkerAndPublishesBoundsOnlyAtBegin() throws Exception {
        ControlledSide side = side(this::close);
        assertTrue(adapterCalls.isEmpty());
        authenticate(side);
        assertEquals(List.of("refresh:rd-host-prepare", "screen:rd-host-prepare"), adapterCalls);
        assertEquals(Packet.screenInfo(1920, 1080).sizePayload(), side.screen().orElseThrow());
        assertEquals(0, boundsPublished.get());
        port.atomically(stx -> side.onActive(stx, 0, imageChannel()));
        assertEquals(1, boundsPublished.get());
        side.onClosing().get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredPrepareDiscardsLateGeometryAndLateFailure(boolean fail) throws Exception {
            if (fail) adapterFailure = new IllegalStateException("late adapter failure");
            adapterGate = new CountDownLatch(1);
            ControlledSide side = side(this::close);
            port.atomically(stx -> { side.onAuthenticated(stx); return null; });
            assertTrue(adapterEntered.await(5, TimeUnit.SECONDS));
            expired.set(true);
            adapterGate.countDown();
            lifecycle.socketsClosed().get(5, TimeUnit.SECONDS);
            assertEquals(Packet.DisconnectReason.TIMEOUT, lifecycle.closeInfo().orElseThrow().reason());
            assertEquals("attach timeout", lifecycle.closeInfo().orElseThrow().message());
            assertTrue(side.screen().isEmpty());
            assertTrue(side.preparationFailure().isEmpty());
            assertFalse(side.readyForActive());
            assertEquals(0, boundsPublished.get());
    }

    @Test void adapterFailureClosesWithoutBegin() throws Exception {
        adapterFailure = new IllegalStateException("secret adapter details");
        ControlledSide side = side(this::close);
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        lifecycle.socketsClosed().get(5, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lifecycle.closeInfo().orElseThrow().reason());
        assertEquals("host screen unavailable", lifecycle.closeInfo().orElseThrow().message());
        assertSame(adapterFailure, side.preparationFailure().orElseThrow());
        assertTrue(side.screen().isEmpty());
        assertFalse(side.readyForActive());
        assertEquals(0, side.inputBeginCount());
        assertTrue(side.onClosing().isDone());
    }

    @Test void closeBeforePrepareBodySkipsAdapters() throws Exception {
        workerGate = new CountDownLatch(1);
        ControlledSide side = side(this::close);
        port.atomically(stx -> { side.onAuthenticated(stx); return null; });
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
        close();
        assertFalse(lifecycle.terminated().isDone());
        workerGate.countDown();
        lifecycle.terminated().get(5, TimeUnit.SECONDS);
        assertTrue(adapterCalls.isEmpty());
        assertTrue(side.screen().isEmpty());
        assertEquals(0, side.inputBeginCount());
        assertEquals(0, side.senderRuns());
    }
}
