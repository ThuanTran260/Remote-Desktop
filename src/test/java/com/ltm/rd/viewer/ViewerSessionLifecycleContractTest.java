package com.ltm.rd.viewer;

import static com.ltm.rd.common.FakeClock.s;
import static com.ltm.rd.common.SessionCloseProbe.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SideTx;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ViewerSessionLifecycleContractTest {
    private static final byte[] TOKEN = new byte[Protocol.TOKEN_LEN];

    private static final class Fixture implements AutoCloseable {
        final FakeClock clock = new FakeClock();
        final ManualScheduler scheduler = new ManualScheduler();
        final CleanupQueue cleanup = new CleanupQueue();
        final Ticker ticker = new Ticker(java.time.Duration.ofHours(1), clock);
        final List<Wire> wires = new ArrayList<>();
        final AtomicBoolean commandMarked = new AtomicBoolean();
        final AtomicBoolean markedBeforeReader = new AtomicBoolean();
        ViewerSession session;
        Fixture() { this(1, 2, SocketConnector.DEFAULT, ViewerHooks.none(), false); }
        Fixture(int cmd, int img, SocketConnector connector, ViewerHooks hooks, boolean markCommand) {
            clock.set(s(1));
            session = new ViewerSession(new ViewerConfig("127.0.0.1", cmd, img, "123456", Timeouts.DEFAULTS,
                    () -> {
                        if (markCommand && session != null) {
                            Channel channel = commandChannel(session);
                            if (channel != null && commandMarked.compareAndSet(false, true)) {
                                markedBeforeReader.set(session.lifecycle().liveWorkers() == 1);
                                channel.in().mark(Protocol.CMD_MAX_LEN);
                            }
                        }
                        return clock.getAsLong();
                    }), 53, connector, hooks, ticker, scheduler, cleanup);
        }
        Wire wire() {
            Wire wire = new Wire();
            wires.add(wire);
            return wire;
        }
        Wire command() {
            assertTrue(session.lifecycle().<Boolean>atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING)));
            assertFalse(session.port().atomically(SideTx::isActivationPending));
            Wire wire = wire();
            session.lifecycle().register(wire.socket);
            assertTrue(session.attachCommandChannel(wire.channel));
            return wire;
        }
        Wire image() {
            Wire wire = wire();
            session.lifecycle().register(wire.socket);
            session.attachImageChannel(wire.channel);
            return wire;
        }
        void finish() throws Exception {
            clock.advance(Timeouts.DEFAULTS.closeGrace());
            scheduler.fireAll();
            cleanup.runAll();
            session.socketsClosed().get(5, TimeUnit.SECONDS);
            session.terminated().get(5, TimeUnit.SECONDS);
            session.inputReleased().get(5, TimeUnit.SECONDS);
            assertEquals(0, session.lifecycle().liveWorkers());
        }
        @Override public void close() throws Exception {
            try {
                wires.forEach(Wire::release);
                session.cancel();
                finish();
            } finally {
                for (Wire wire : wires) wire.socket.close();
                ticker.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test void startAfterCancelIsRefused() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        try (Fixture f = new Fixture(1, 2, (socket, address, timeout) -> connects.incrementAndGet(),
                ViewerHooks.none(), false)) {
            f.session.cancel();
            assertFalse(f.session.start());
            assertEquals(0, connects.get());
            assertEquals(0, f.session.lifecycle().liveWorkers());
            assertTrue(f.session.activated().isCompletedExceptionally());
            assertTrue(f.session.inputReleased().isDone());
            f.finish();
            assertEquals(0, f.ticker.registrations());
        }
    }

    @Test void commandHandoverRejectsNonAuthenticatingPhases() throws Exception {
        try (Fixture f = new Fixture()) {
            Wire unclaimed = f.wire();
            assertFalse(f.session.attachCommandChannel(unclaimed.channel));
            assertNull(writer(f.session));
            Wire accepted = f.command();
            await(accepted.input.entered);
            CommandWriter original = writer(f.session);
            int workers = f.session.lifecycle().liveWorkers();
            assertFalse(f.session.attachCommandChannel(unclaimed.channel));
            assertSame(original, writer(f.session));
            assertEquals(workers, f.session.lifecycle().liveWorkers());
            assertFalse(f.session.imageChannelReady());
            f.session.cancel();
            CloseInfo reason = f.session.lifecycle().closeInfo().orElseThrow();
            assertFalse(f.session.attachCommandChannel(unclaimed.channel));
            assertSame(reason, f.session.lifecycle().closeInfo().orElseThrow());
        }
    }

    @Test void realPortReportsPendingAndDeadlineBoundaries() throws Exception {
        for (long started : new long[] {s(1), Long.MAX_VALUE - s(3)}) {
            try (Fixture f = new Fixture()) {
                f.clock.set(started);
                assertEquals(53, f.session.port().generation());
                assertFalse(f.session.port().atomically(SideTx::isActivationPending));
                f.command();
                assertTrue(f.session.port().atomically(SideTx::isActivationPending));
                long deadline = started + s(7);
                for (int delta : new int[] {-1, 0, 1}) {
                    f.clock.set(deadline + delta);
                    assertEquals(delta >= 0, f.session.port().atomically(SideTx::activationExpired));
                    assertEquals(ViewerPhase.ATTACHING, f.session.phase());
                    assertFalse(f.session.activated().isDone());
                }
                AtomicReference<SideTx> expired = new AtomicReference<>();
                f.session.port().atomically(tx -> { expired.set(tx); return null; });
                assertThrows(IllegalStateException.class, () -> expired.get().isActive());
                f.session.cancel();
                assertFalse(f.session.port().atomically(SideTx::isActivationPending));
            }
        }
        try (Fixture f = new Fixture()) {
            f.command();
            f.session.dispatch(Packet.screenInfo(8, 8));
            f.image();
            f.session.dispatch(Packet.sessionActive());
            f.session.activated().get(5, TimeUnit.SECONDS);
            assertTrue(f.session.port().atomically(SideTx::isActive));
            assertFalse(f.session.port().atomically(SideTx::isActivationPending));
        }
    }

    @Test void commandHandoverAppliesTimeoutBeforeReaderAndHandlesSetterFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.session.lifecycle().<Boolean>atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING)));
            Wire wire = f.wire();
            f.session.lifecycle().register(wire.socket);
            wire.socket.setSoTimeout(5_000);
            AtomicInteger readerTimeout = new AtomicInteger();
            AtomicBoolean underLock = new AtomicBoolean();
            wire.socket.onTimeout = () -> underLock.set(lockHeld(f.session.lifecycle()));
            wire.input.beforeRead = () -> readerTimeout.set(wire.socket.timeout);
            wire.input.timeout = true;
            assertTrue(f.session.attachCommandChannel(wire.channel));
            await(wire.input.entered);
            assertEquals(10_000, readerTimeout.get());
            assertFalse(underLock.get());
            awaitCondition(() -> f.session.lifecycle().isClosed());
            assertEquals("command read timeout", f.session.lifecycle().closeInfo().orElseThrow().message());
        }
        try (Fixture f = new Fixture()) {
            assertTrue(f.session.lifecycle().<Boolean>atomically(tx -> tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING)));
            Wire wire = f.wire();
            f.session.lifecycle().register(wire.socket);
            wire.socket.failTimeout = true;
            assertTrue(f.session.attachCommandChannel(wire.channel));
            assertEquals("cannot set read timeout", f.session.lifecycle().closeInfo().orElseThrow().message());
            assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, f.session.lifecycle().closeInfo().orElseThrow().reason());
            assertEquals(1, wire.input.entered.getCount());
            assertTrue(f.session.activated().isCompletedExceptionally());
        }
    }

    @Test void gracefulCloseWaitsForFinalFlushAndSuppressesEcho() throws Exception {
        for (boolean send : new boolean[] {true, false}) {
            try (Fixture f = new Fixture()) {
                Wire wire = f.command();
                CommandWriter writer = writer(f.session);
                assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(6)));
                awaitCondition(() -> wire.output.flushes.get() == 1);
                wire.output.hold = true;
                if (!send) assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(7)));
                if (!send) await(wire.output.entered);
                f.session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "test close", send));
                await(wire.output.entered);
                CompletableFuture<Void> cleanup = f.cleanup.runOneUntilWaitingOrDone();
                assertFalse(f.session.socketsClosed().isDone(), "raw close must wait for the held graceful write");
                assertFalse(wire.socket.isClosed());
                wire.release();
                cleanup.get(5, TimeUnit.SECONDS);
                f.session.terminated().get(5, TimeUnit.SECONDS);
                List<Packet> packets = wire.packets();
                assertEquals(2, packets.size());
                assertEquals(Packet.pong(6), packets.getFirst());
                assertEquals(send ? PacketType.DISCONNECT : PacketType.PONG, packets.getLast().type());
                if (send) assertEquals(Packet.DisconnectReason.USER, packets.getLast().disconnectPayload().reason());
            }
        }
    }

    @Test void forcedCloseSettlesWriterBeforeHeldBodyReturns() throws Exception {
        for (boolean holdPhaseA : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                Wire wire = f.command();
                CommandWriter writer = writer(f.session);
                wire.output.hold = true;
                assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(7)));
                await(wire.output.entered);
                assertEquals(CommandWriter.OfferResult.ACCEPTED, writer.offer(Packet.pong(8)));
                CountDownLatch hookEntered = new CountDownLatch(1);
                CountDownLatch hookRelease = new CountDownLatch(1);
                CompletableFuture<Void> closed = new CompletableFuture<>();
                CompletableFuture<CommandWriter.OfferResult> stopped = new CompletableFuture<>();
                if (holdPhaseA) f.session.lifecycle().addCloseHook(() -> {
                    hookEntered.countDown();
                    try { hookRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                f.session.lifecycle().addStopHook(() -> stopped.complete(writer.offer(Packet.pong(9))));
                Thread closer = new Thread(() -> {
                    try { f.session.cancel(); closed.complete(null); }
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
                    assertTrue(f.session.lifecycle().liveWorkers() >= 1);
                    assertFalse(f.session.terminated().isDone());
                } finally {
                    hookRelease.countDown();
                    wire.release();
                    closer.join(5_000);
                    assertFalse(closer.isAlive());
                }
                closed.get(5, TimeUnit.SECONDS);
                f.finish();
                assertTrue(wire.packets().isEmpty(), "forced raw close prevents the held packet from reaching the peer");
            }
        }
    }

    @Test void lateMatchingPongDoesNotChangeClosedRtt() throws Exception {
        try (Fixture f = new Fixture()) {
            Wire wire = f.command();
            f.clock.set(s(3));
            heartbeat(f.session).tick(s(3));
            awaitCondition(() -> wire.output.flushes.get() > 0);
            int sequence = wire.packets().getFirst().seq();
            f.session.cancel();
            f.finish();
            var rtt = f.session.rttNanos();
            CloseInfo reason = f.session.lifecycle().closeInfo().orElseThrow();
            byte[] sent = wire.output.bytes();
            f.clock.set(s(4));
            f.session.dispatch(Packet.pong(sequence));
            assertEquals(rtt, f.session.rttNanos());
            assertSame(reason, f.session.lifecycle().closeInfo().orElseThrow());
            assertArrayEquals(sent, wire.output.bytes());
            assertTrue(f.session.inputReleased().isDone());
        }
    }

    @Test void screenInfoAtActivationDeadlineClosesWithoutTickerOrImageHandover() throws Exception {
        try (Fixture f = new Fixture()) {
            f.command();
            f.clock.set(s(8));
            f.session.dispatch(Packet.screenInfo(8, 8));
            assertEquals(ViewerPhase.CLOSING, f.session.phase());
            assertEquals("activation timeout", f.session.lifecycle().closeInfo().orElseThrow().message());
            assertEquals(Packet.DisconnectReason.TIMEOUT, f.session.lifecycle().closeInfo().orElseThrow().reason());
            assertTrue(f.session.activated().isCompletedExceptionally());
            assertTrue(f.session.inputReleased().isDone());
            f.session.attachImageChannel(f.wire().channel);
            f.session.dispatch(Packet.sessionActive());
            assertFalse(f.session.imageChannelReady());
        }
    }

    @Test void imageConnectFailureAfterTcpConnectNeverContinuesAttach() throws Exception {
        try (ServerSocket cmd = new ServerSocket(0); ServerSocket img = new ServerSocket(0)) {
            cmd.setSoTimeout(5_000);
            img.setSoTimeout(5_000);
            AtomicInteger hooks = new AtomicInteger();
            AtomicReference<Thread> connectWorker = new AtomicReference<>();
            SocketConnector connector = (socket, address, timeout) -> {
                connectWorker.set(Thread.currentThread());
                socket.connect(address, timeout);
                if (((InetSocketAddress) address).getPort() == img.getLocalPort()) throw new IOException("connected image failure");
            };
            try (Fixture f = new Fixture(cmd.getLocalPort(), img.getLocalPort(), connector,
                    new ViewerHooks(hooks::incrementAndGet), false)) {
                assertTrue(f.session.start());
                try (Socket command = cmd.accept()) {
                    command.setSoTimeout(5_000);
                    assertEquals(PacketType.AUTH, read(command).type());
                    send(command, Packet.authResult(Packet.AuthStatus.OK, TOKEN));
                    try (Socket image = img.accept()) {
                        connectWorker.get().join(5_000);
                        assertFalse(connectWorker.get().isAlive());
                        assertEquals(0, hooks.get());
                        assertEquals(0, image.getInputStream().available());
                        assertFalse(f.session.imageChannelReady());
                        assertEquals("connect failed", f.session.lifecycle().closeInfo().orElseThrow().message());
                        assertTrue(f.session.activated().isCompletedExceptionally());
                    }
                }
            }
        }
    }

    @Test void handshakeCloseReleasesBothOriginalInputWrappers() throws Exception {
        try (ServerSocket cmd = new ServerSocket(0); ServerSocket img = new ServerSocket(0)) {
            cmd.setSoTimeout(5_000);
            img.setSoTimeout(5_000);
            try (Fixture f = new Fixture(cmd.getLocalPort(), img.getLocalPort(), SocketConnector.DEFAULT,
                    ViewerHooks.none(), true)) {
                assertTrue(f.session.start());
                try (Socket command = cmd.accept()) {
                    command.setSoTimeout(5_000);
                    assertEquals(PacketType.AUTH, read(command).type());
                    send(command, Packet.authResult(Packet.AuthStatus.OK, TOKEN));
                    try (Socket image = img.accept()) {
                        image.setSoTimeout(5_000);
                        assertEquals(PacketType.ATTACH, read(image).type());
                        awaitCondition(f.session::imageChannelReady);
                        Channel originalCommand = commandChannel(f.session);
                        Channel originalImage = imageChannel(f.session);
                        assertTrue(f.commandMarked.get());
                        assertTrue(f.markedBeforeReader.get(), "mark precedes all deferred reader starts");
                        originalImage.in().mark(Protocol.CMD_MAX_LEN);
                        f.session.cancel();
                        f.finish();
                        assertTrue(originalCommand.socket().isClosed());
                        assertTrue(originalImage.socket().isClosed());
                        assertThrows(IOException.class, originalCommand.in()::reset);
                        assertThrows(IOException.class, originalImage.in()::reset);
                    }
                }
            }
        }
    }

    private static Packet read(Socket socket) throws IOException {
        return PacketIO.read(new DataInputStream(socket.getInputStream()), Protocol.CMD_MAX_LEN,
                EnumSet.allOf(PacketType.class)).orElseThrow();
    }

    private static void send(Socket socket, Packet packet) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        PacketIO.write(out, packet);
        out.flush();
    }
}
