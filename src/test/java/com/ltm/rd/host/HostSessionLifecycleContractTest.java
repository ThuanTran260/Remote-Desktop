package com.ltm.rd.host;

import static com.ltm.rd.common.FakeClock.s;
import static com.ltm.rd.common.SessionCloseProbe.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.SideTx;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class HostSessionLifecycleContractTest {
    private static final byte[] TOKEN = new byte[Protocol.TOKEN_LEN];

    private static final class Fixture implements AutoCloseable {
        final FakeClock clock = new FakeClock();
        final ManualScheduler scheduler = new ManualScheduler();
        final CleanupQueue cleanup = new CleanupQueue();
        final Ticker ticker = new Ticker(java.time.Duration.ofHours(1), clock);
        final HostTestAccess.HostResources resources = new HostTestAccess.HostResources();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Wire> wires = new ArrayList<>();
        final CountDownLatch prepared = new CountDownLatch(1);
        final SessionLifecycle<HostPhase> lifecycle;
        final HostSession session;
        Fixture() {
            clock.set(s(1));
            resources.source = new FakeScreenSource(8, 8);
            lifecycle = new SessionLifecycle<>(HostPhase.RESERVED, HostPhase.CLOSING, clock, GRACE,
                    scheduler, cleanup, body -> new Thread(() -> {
                        try { body.run(); }
                        finally {
                            if (Thread.currentThread().getName().equals("rd-host-prepare")) prepared.countDown();
                        }
                    }) {
                        @Override public void interrupt() { events.add("interrupt"); super.interrupt(); }
                    });
            session = new HostSession(53, TOKEN, InetAddress.getLoopbackAddress(), s(1), lifecycle,
                    resources.env(Timeouts.DEFAULTS, clock, ticker, () -> false, null, null));
        }
        Wire wire(String lane) {
            Wire wire = new Wire(events, lane);
            wires.add(wire);
            return wire;
        }
        Wire command() throws Exception {
            Wire wire = wire("command");
            assertTrue(session.attachCommandChannel(new PendingConnection(wire.socket, clock.getAsLong()),
                    wire.channel, clock.getAsLong()));
            await(prepared);
            awaitCondition(() -> wire.output.flushes.get() >= 1);
            return wire;
        }
        Wire image() {
            Wire wire = wire("image");
            assertEquals(HostSession.AttachResult.ATTACHED, session.attachImage(Packet.attach(TOKEN).attachPayload(),
                    new PendingConnection(wire.socket, clock.getAsLong()), wire.channel, clock.getAsLong()));
            return wire;
        }
        Wire active() throws Exception {
            Wire command = command();
            image();
            assertEquals(HostPhase.ACTIVE, session.phase());
            awaitCondition(() -> command.output.flushes.get() >= 2);
            return command;
        }
        void finish() throws Exception {
            clock.advance(Timeouts.DEFAULTS.closeGrace());
            scheduler.fireAll();
            cleanup.runAll();
            session.socketsClosed().get(5, TimeUnit.SECONDS);
            session.terminated().get(5, TimeUnit.SECONDS);
            session.inputReleased().get(5, TimeUnit.SECONDS);
            assertEquals(0, lifecycle.liveWorkers());
        }
        @Override public void close() throws Exception {
            try {
                wires.forEach(Wire::release);
                session.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
                finish();
            } finally {
                for (Wire wire : wires) wire.socket.close();
                try { resources.close(); }
                finally { ticker.close(); scheduler.shutdownNow(); }
            }
        }
    }

    @Test void realPortReportsGenerationAndRolePhases() throws Exception {
        try (Fixture f = new Fixture()) {
            assertNotNull(f.session.port());
            assertEquals(53, f.session.port().generation());
            assertFalse(f.session.port().atomically(SideTx::isActive));
            assertTrue(f.session.port().atomically(SideTx::isActivationPending));
            assertFalse(f.session.port().atomically(SideTx::activationExpired));
            f.active();
            assertTrue(f.session.port().atomically(SideTx::isActive));
            assertFalse(f.session.port().atomically(SideTx::isActivationPending));
            assertTrue(f.session.port().close(new CloseInfo(Packet.DisconnectReason.USER, "port close", false)));
            assertTrue(f.session.port().isClosed());
            assertFalse(f.session.port().atomically(SideTx::isActive));
            assertFalse(f.session.port().atomically(SideTx::isActivationPending));
            assertEquals("port close", f.session.closeInfo().orElseThrow().message());
        }
    }

    /**
     * After ATTACH the Host only writes frames on the image channel and never reads it, so it arms no read
     * timeout there: the Viewer owns the image read timeout. A socket whose setter would fail (or that records
     * any setter call) therefore does not affect the hand-over.
     */
    @Test void imageHandoverArmsNoReadTimeoutOnTheSendOnlyHostSocket() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                Wire image = f.wire("image");
                image.socket.failTimeout = fail;
                AtomicInteger setterCalls = new AtomicInteger();
                image.socket.onTimeout = setterCalls::incrementAndGet;
                assertEquals(HostSession.AttachResult.ATTACHED,
                        f.session.attachImage(Packet.attach(TOKEN).attachPayload(),
                                new PendingConnection(image.socket, s(1)), image.channel, s(1)));
                assertEquals(0, setterCalls.get(), "the Host never reads the image channel after ATTACH");
                assertEquals(0, image.socket.timeout);
                assertTrue(f.session.closeInfo().isEmpty(), "no setter, so no setter failure can close the session");
                assertEquals(HostPhase.RESERVED, f.session.phase());
                Wire rejected = f.wire("duplicate");
                assertEquals(HostSession.AttachResult.REJECTED,
                        f.session.attachImage(Packet.attach(TOKEN).attachPayload(),
                                new PendingConnection(rejected.socket, s(1)), rejected.channel, s(1)));
                assertEquals(0, rejected.socket.timeout);
            }
        }
    }

    @Test void gracefulCloseWaitsForFinalFlushAndSuppressesEcho() throws Exception {
        for (boolean send : new boolean[] {true, false}) {
            try (Fixture f = new Fixture()) {
                Wire wire = f.command();
                CommandWriter writer = writer(f.session);
                wire.output.hold = true;
                if (!send) assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(7)));
                if (!send) await(wire.output.entered);
                try (GracefulWait graceful = new GracefulWait(f.lifecycle)) {
                    f.session.close(new CloseInfo(Packet.DisconnectReason.USER, "test close", send));
                    await(wire.output.entered);
                    try (CloseTask cleanup = f.cleanup.startOne()) {
                        try {
                            graceful.awaitPendingOrClose(f.session.socketsClosed(), cleanup);
                            assertFalse(f.session.socketsClosed().isDone(), "raw close must wait for the held graceful write");
                            assertFalse(wire.socket.isClosed());
                        } finally {
                            wire.release();
                            graceful.release();
                        }
                        cleanup.done.get(5, TimeUnit.SECONDS);
                        f.session.terminated().get(5, TimeUnit.SECONDS);
                        var packets = wire.packets();
                        assertEquals(2, packets.size());
                        assertEquals(PacketType.SCREEN_INFO, packets.getFirst().type());
                        assertEquals(send ? PacketType.DISCONNECT : PacketType.PONG, packets.getLast().type());
                        if (send) assertEquals(Packet.DisconnectReason.USER, packets.getLast().disconnectPayload().reason());
                    }
                }
            }
        }
    }

    @Test void forcedCloseSettlesWriterBeforeHeldBodyReturns() throws Exception {
        for (boolean holdPhaseA : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                Wire wire = f.command();
                CommandWriter writer = writer(f.session);
                int bytesBefore = wire.output.bytes().length;
                wire.output.hold = true;
                assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(7)));
                await(wire.output.entered);
                assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(8)));
                CountDownLatch hookEntered = new CountDownLatch(1);
                CountDownLatch hookRelease = new CountDownLatch(1);
                CompletableFuture<Void> closed = new CompletableFuture<>();
                CompletableFuture<CommandWriter.OfferResult> stopped = new CompletableFuture<>();
                if (holdPhaseA) f.lifecycle.addCloseHook(() -> {
                    hookEntered.countDown();
                    try { hookRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                f.lifecycle.addStopHook(() -> stopped.complete(writer.offer(Packet.pong(9))));
                Thread closer = new Thread(() -> {
                    try { f.session.disconnect(); closed.complete(null); }
                    catch (Throwable failure) { closed.completeExceptionally(failure); }
                });
                closer.start();
                try {
                    if (holdPhaseA) await(hookEntered); else closed.get(5, TimeUnit.SECONDS);
                    CompletableFuture<?> stage = closingStage(writer);
                    if (!holdPhaseA) assertNotNull(stage);
                    f.clock.advance(Timeouts.DEFAULTS.closeGrace());
                    f.scheduler.fireAll();
                    f.cleanup.runAll();
                    assertEquals(CommandWriter.OfferResult.CLOSED, stopped.get(5, TimeUnit.SECONDS));
                    assertTrue(queued(writer).isEmpty());
                    if (stage != null) assertTrue(stage.isDone(), "forced stop settles the held graceful stage");
                    assertTrue(writer.closeGracefully(null).isDone());
                    assertTrue(f.lifecycle.liveWorkers() >= 1);
                    assertFalse(f.session.terminated().isDone());
                } finally {
                    hookRelease.countDown();
                    wire.release();
                    closer.join(5_000);
                    assertFalse(closer.isAlive());
                }
                closed.get(5, TimeUnit.SECONDS);
                f.finish();
                assertEquals(bytesBefore, wire.output.bytes().length);
            }
        }
    }

    @Test void closeReleasesBothChannelWrappersAfterRawSockets() throws Exception {
        try (Fixture f = new Fixture()) {
            Wire command = f.command();
            Wire image = f.image();
            command.output.failClose = true;
            image.output.failClose = true;
            f.session.disconnect();
            f.finish();
            for (Wire wire : List.of(command, image)) {
                assertTrue(wire.socket.isClosed());
                assertTrue(wire.output.wrapperClosed);
                assertTrue(wire.input.wrapperClosed, "output close failure must not skip input close");
            }
            int firstInterrupt = f.events.indexOf("interrupt");
            assertTrue(firstInterrupt >= 0);
            assertTrue(f.events.indexOf("command:raw-close") < firstInterrupt);
            assertTrue(f.events.indexOf("image:raw-close") < firstInterrupt);
            assertTrue(firstInterrupt < f.events.indexOf("command:output-close"));
            assertTrue(firstInterrupt < f.events.indexOf("image:output-close"));
            assertTrue(f.events.indexOf("command:output-close") < f.events.indexOf("command:input-close"));
            assertTrue(f.events.indexOf("image:output-close") < f.events.indexOf("image:input-close"));
        }
    }

    @Test void acceptedPacketsResetLivenessAndMatchingPongUpdatesRtt() throws Exception {
        try (Fixture f = new Fixture()) {
            Wire command = f.active();
            f.clock.set(s(3));
            heartbeat(f.session).tick(s(3));
            awaitCondition(() -> command.output.flushes.get() >= 3);
            int seq = command.packets().getLast().seq();
            assertTrue(f.session.rttNanos().isEmpty());
            f.clock.set(s(4));
            f.session.dispatch(Packet.pong(seq + 1));
            assertTrue(f.session.rttNanos().isEmpty());
            f.session.dispatch(Packet.pong(seq));
            assertEquals(java.util.OptionalLong.of(s(1)), f.session.rttNanos());
            f.clock.set(s(7));
            f.session.dispatch(Packet.pong(seq + 99));
            assertEquals(java.util.OptionalLong.of(s(1)), f.session.rttNanos());
            f.clock.set(s(8));
            heartbeat(f.session).tick(s(8));
            assertFalse(f.session.closeInfo().isPresent());
            f.clock.set(s(15));
            heartbeat(f.session).tick(s(15));
            assertEquals(Packet.DisconnectReason.TIMEOUT, f.session.closeInfo().orElseThrow().reason());
            assertEquals("liveness timeout", f.session.closeInfo().orElseThrow().message());
        }
    }

    @Test void lateMatchingPongDoesNotChangeClosedRtt() throws Exception {
        try (Fixture f = new Fixture()) {
            Wire command = f.command();
            f.clock.set(s(3));
            heartbeat(f.session).tick(s(3));
            awaitCondition(() -> command.output.flushes.get() >= 2);
            int seq = command.packets().getLast().seq();
            f.session.disconnect();
            f.finish();
            var rtt = f.session.rttNanos();
            var reason = f.session.closeInfo().orElseThrow();
            byte[] sent = command.output.bytes();
            f.clock.set(s(4));
            f.session.dispatch(Packet.pong(seq));
            assertEquals(rtt, f.session.rttNanos());
            assertSame(reason, f.session.closeInfo().orElseThrow());
            assertArrayEquals(sent, command.output.bytes());
            assertTrue(f.session.inputReleased().isDone());
        }
    }
}
