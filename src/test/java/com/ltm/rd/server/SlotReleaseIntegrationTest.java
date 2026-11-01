package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.FrameEncoder;
import com.ltm.rd.it.RawPeer;
import com.ltm.rd.viewer.DelegatingImageReader;
import com.ltm.rd.viewer.FrameDecoder;
import com.ltm.rd.viewer.FrameReceiver;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The slot and PIN stay owned until every part of the old session has finished closing. */
@Timeout(60)
class SlotReleaseIntegrationTest {
    private static final int WAIT_S = 10;
    private static final InetAddress LO = InetAddress.getLoopbackAddress();
    private final FakeClock clock = new FakeClock();
    private final List<ServerCore> cores = new ArrayList<>();
    private final List<RawPeer> peers = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final List<Thread> releaseWaiters = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        // Always unblock workers before core.close() waits for their termination.
        releases.forEach(CountDownLatch::countDown);
        for (Thread waiter : releaseWaiters) {
            waiter.join(TimeUnit.SECONDS.toMillis(WAIT_S));
            assertFalse(waiter.isAlive(), "input-release fixture stopped");
        }
        peers.forEach(RawPeer::close);
        cores.forEach(ServerCore::close);
        for (ServerCore core : cores) {
            assertEquals(0, core.tickerRegistrations());
            try (ServerSocket cmd = new ServerSocket(core.cmdPort(), 1, LO);
                    ServerSocket img = new ServerSocket(core.imgPort(), 1, LO)) {
                assertTrue(cmd.isBound() && img.isBound(), "both listening ports are released");
            }
        }
        awaitTrue(() -> liveRdThreads().isEmpty(), "all rd-* threads to stop");
    }

    private static Set<Thread> liveRdThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("rd-"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private ServerCore core(ServerCore.Seams seams) throws IOException {
        ServerCore core = new ServerCore(new ServerConfig(LO, 0, 0, Timeouts.DEFAULTS, clock,
                new SecureRandom()), seams);
        core.start();
        cores.add(core);
        return core;
    }

    private RawPeer auth(ServerCore core, Packet.AuthStatus expected) throws IOException {
        RawPeer peer = RawPeer.connect(core.cmdPort());
        peers.add(peer);
        peer.send(Packet.auth(core.sessions().currentCode()));
        assertEquals(expected, peer.read().authResultPayload().status());
        return peer;
    }

    private CountDownLatch releaseLatch() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private record ReleaseGate(CountDownLatch release, CompletableFuture<Void> opened) {
        CompletableFuture<Void> hold(CompletableFuture<Void> source) {
            return opened.thenCompose(ignored -> source);
        }
    }

    private ReleaseGate releaseGate() {
        CountDownLatch release = releaseLatch();
        CompletableFuture<Void> opened = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                awaitUninterruptibly(release);
                opened.complete(null);
            } catch (Throwable failure) {
                opened.completeExceptionally(failure);
            }
        }, "test-input-release");
        waiter.setDaemon(true);
        releaseWaiters.add(waiter);
        waiter.start();
        return new ReleaseGate(release, opened);
    }

    private ServerCore core(UnaryOperator<CompletableFuture<Void>> inputRelease) throws IOException {
        return core(new ServerCore.Seams(null, null, null, false, null, null, inputRelease));
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true; // close interrupts the worker; only the latch may release it.
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @RepeatedTest(20)
    void inputReleasedIsTheSameObjectBeforeAndAfterClose() throws Exception {
        ReleaseGate gate = releaseGate();
        AtomicReference<CompletableFuture<Void>> source = new AtomicReference<>();
        ServerCore core = core(stage -> {
            source.set(stage);
            return gate.hold(stage);
        });
        RawPeer client = auth(core, Packet.AuthStatus.OK);
        ServerSession session = core.sessions().current().orElseThrow();
        CompletableFuture<Void> target = session.inputReleased();
        assertFalse(target.isDone(), "input is owned before close");
        try {
            client.close();
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
            assertSame(target, session.inputReleased());
            assertNotNull(source.get());
            assertNotSame(target, source.get(), "the seam receives only the side's source stage");
            assertTrue(source.get().isDone());
            assertFalse(target.isDone(), "the stable target waits for the transformed source");
        } finally {
            gate.release().countDown();
        }
        target.get(WAIT_S, TimeUnit.SECONDS);
        assertSame(target, session.inputReleased());
    }

    @RepeatedTest(20)
    void slotHeldWhileInputReleaseIsHeld() throws Exception {
        ReleaseGate gate = releaseGate();
        ServerCore core = core(gate::hold);
        RawPeer client = auth(core, Packet.AuthStatus.OK);
        ServerSession session = core.sessions().current().orElseThrow();
        String code = core.sessions().currentCode();
        try {
            client.close();
            session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
            assertFalse(session.inputReleased().isDone());
            assertSame(session, core.sessions().current().orElse(null));
            assertEquals(code, core.sessions().currentCode());
            auth(core, Packet.AuthStatus.BUSY);
        } finally {
            gate.release().countDown();
        }
        session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "input release to free the slot");
        assertNotEquals(code, core.sessions().currentCode());
        auth(core, Packet.AuthStatus.OK);
    }

    @Test
    void slotNeverReleasedWhenInputReleaseFails() throws Exception {
        IllegalStateException failure = new IllegalStateException("input release failed");
        failedRelease(source -> CompletableFuture.failedFuture(failure), failure);
    }

    @Test
    void nullReleaseStageFailsTheTargetAndKeepsTheSlotBusy() throws Exception {
        assertInstanceOf(NullPointerException.class, failedRelease(source -> null, null));
    }

    @Test
    void throwingReleaseSeamFailsTheTargetAndKeepsTheSlotBusy() throws Exception {
        IllegalArgumentException failure = new IllegalArgumentException("release seam failed");
        failedRelease(source -> { throw failure; }, failure);
    }

    private Throwable failedRelease(UnaryOperator<CompletableFuture<Void>> seam, Throwable expected)
            throws Exception {
        ServerCore core = core(seam);
        RawPeer client = auth(core, Packet.AuthStatus.OK);
        ServerSession session = core.sessions().current().orElseThrow();
        String code = core.sessions().currentCode();
        CompletableFuture<Void> target = session.inputReleased();
        assertFalse(target.isDone());
        client.close();
        session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> target.get(WAIT_S, TimeUnit.SECONDS));
        if (expected != null) assertSame(expected, failure.getCause());
        assertSame(target, session.inputReleased());
        assertSame(session, core.sessions().current().orElse(null));
        assertEquals(code, core.sessions().currentCode());
        auth(core, Packet.AuthStatus.BUSY);
        session.disconnect(); // repeated close must not turn a failed release into success.
        assertTrue(target.isCompletedExceptionally());
        assertSame(session, core.sessions().current().orElse(null));
        return failure.getCause();
    }

    @RepeatedTest(20)
    void slotHeldUntilWorkersStop() throws Exception {
        ServerCore core = core(new ServerCore.Seams(null, null, null, false));
        RawPeer client = auth(core, Packet.AuthStatus.OK);
        ServerSession session = core.sessions().current().orElseThrow();
        String code = core.sessions().currentCode();
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = releaseLatch();
        CountDownLatch stopHooks = new CountDownLatch(1);
        session.lifecycle().addStopHook(stopHooks::countDown);
        byte[] jpeg = new FrameEncoder(8192, 0.6f)
                .encode(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB)).jpeg();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        PacketIO.write(new DataOutputStream(wire), Packet.frame(16, 9, jpeg));
        Channel channel = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(wire.toByteArray())),
                new DataOutputStream(OutputStream.nullOutputStream()));
        DelegatingImageReader reader = new DelegatingImageReader();
        FrameDecoder decoder = new FrameDecoder(() -> {
            decoding.countDown();
            awaitUninterruptibly(release);
            return reader;
        });
        assertTrue(session.lifecycle().startWorker("rd-held-frame-decoder",
                new FrameReceiver(session.port(), session.controlSide(), channel, decoder, 16, 9, clock)));
        try {
            assertTrue(decoding.await(WAIT_S, TimeUnit.SECONDS));
            client.close();
            session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
            assertTrue(stopHooks.await(WAIT_S, TimeUnit.SECONDS));
            assertFalse(session.terminated().isDone(), "the decoder ignores close's interrupt");
            assertTrue(session.inputReleased().isDone(), "only the worker still owns the slot");
            assertSame(session, core.sessions().current().orElse(null));
            assertEquals(code, core.sessions().currentCode());
            auth(core, Packet.AuthStatus.BUSY);
        } finally {
            release.countDown();
        }
        session.terminated().get(WAIT_S, TimeUnit.SECONDS);
        awaitTrue(() -> core.sessions().current().isEmpty(), "the slot after the worker stops");
        assertNotEquals(code, core.sessions().currentCode());
        assertEquals(1, reader.readCalls.get(), "the held worker really decodes through the JPEG reader");
        assertEquals(1, reader.disposeCalls.get());
        auth(core, Packet.AuthStatus.OK);
    }
}
