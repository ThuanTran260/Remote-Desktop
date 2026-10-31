package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The defensive branches of {@link ServerSession}: packets the table of spec 2.4 forbids, a hand-over
 * the session must refuse, frames that must be dropped, and every path where a full command queue
 * closes the session with OVERFLOW. Each ordering is forced with the fake clock, direct calls to the
 * session and a gate inside the writer's sink, so nothing depends on thread scheduling.
 */
@Timeout(120)
class ServerSessionProtocolEdgesTest {
    private static final int WAIT_S = 10;
    private static final byte[] TOKEN = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(2), Duration.ofSeconds(8),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(3),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));

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
    private final FakeClock clock = new FakeClock();
    private final List<ServerSession> sessions = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void shutdown() {
        gates.forEach(CountDownLatch::countDown);
        for (ServerSession session : sessions) {
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        }
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** Bytes become visible only on flush; an optional gate blocks the writer inside its first write. */
    private static final class Sink extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream flushed = new ByteArrayOutputStream();
        private final CountDownLatch gate;
        private int flushes;
        final CountDownLatch entered = new CountDownLatch(1);

        Sink(CountDownLatch gate) {
            this.gate = gate;
        }

        private void block() {
            if (gate == null) return;
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void write(int b) {
            block();
            synchronized (this) {
                pending.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            block();
            synchronized (this) {
                pending.write(b, off, len);
            }
        }

        @Override
        public synchronized void flush() {
            flushed.write(pending.toByteArray(), 0, pending.size());
            pending.reset();
            flushes++;
            notifyAll();
        }

        synchronized byte[] snapshot() {
            return flushed.toByteArray();
        }

        synchronized int flushCount() {
            return flushes;
        }

        /** Waits (bounded) for a flush after the one that made the count {@code seen}. */
        synchronized boolean awaitFlushAfter(int seen, long deadlineNanos) throws InterruptedException {
            while (flushes == seen) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    /** A command-channel input that blocks until closed, like an idle peer. */
    private static final class IdleInput extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private Sink gatedSink() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return new Sink(gate);
    }

    private ServerSession newSession() {
        SessionLifecycle<ServerPhase> lifecycle = new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        ServerSession.Env env = new ServerSession.Env(TIMEOUTS, clock, null, () -> false, null, false);
        ServerSession session = new ServerSession(1, TOKEN, InetAddress.getLoopbackAddress(), 0, lifecycle, env);
        sessions.add(session);
        return session;
    }

    private static Channel cmdChannel(PendingConnection pc, Sink sink) {
        return new Channel(pc.socket(), new DataInputStream(new IdleInput()), new DataOutputStream(sink));
    }

    /** RESERVED, command channel attached at time 0, no SCREEN_INFO and no image channel. */
    private ServerSession reserved(Sink sink) {
        ServerSession session = newSession();
        PendingConnection pc = new PendingConnection(new Socket(), 0);
        assertTrue(session.attachCommandChannel(pc, cmdChannel(pc, sink), 0));
        return session;
    }

    private static void attachImage(ServerSession session, long now) {
        PendingConnection pc = new PendingConnection(new Socket(), now);
        Channel img = new Channel(pc.socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        session.attachImage(Packet.attach(TOKEN).attachPayload(), pc, img, now);
    }

    private ServerSession active(Sink sink) {
        ServerSession session = reserved(sink);
        session.dispatch(Packet.screenInfo(1920, 1080));
        attachImage(session, 0);
        assertEquals(ServerPhase.ACTIVE, session.phase());
        return session;
    }

    private static BufferedImage image() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    private static void awaitEntered(Sink sink) throws InterruptedException {
        assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS), "the writer is stuck inside its first write");
    }

    /** With the writer stuck in its first write, fills the 256-slot queue with input the gate lets through. */
    private static void fillWithInput(ServerSession session) {
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) {
            assertEquals(InputOutcome.SENT, session.offerInput(0, Packet.keyPress(i + 1)), "fill " + i);
        }
    }

    /** Lets the first PONG into the writer (which then blocks) and queues 256 more, so the queue is full. */
    private static void fillWithPongs(ServerSession session, Sink sink) throws InterruptedException {
        session.dispatch(Packet.ping(1));
        awaitEntered(sink);
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) session.dispatch(Packet.ping(i + 2));
        assertFalse(session.lifecycle().isClosed(), "a queue that is exactly full is not an overflow yet");
    }

    private static CloseInfo closeInfo(ServerSession session) {
        return session.closeInfo().orElseThrow();
    }

    // ---- command channel hand-over ---------------------------------------------------------

    @Test
    void secondCommandChannelIsRefusedAndTheFirstKeepsRunning() {
        ServerSession session = reserved(new Sink(null));
        PendingConnection second = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(second, cmdChannel(second, new Sink(null)), 0));

        assertTrue(second.claimForClose(), "a refused connection stays claimable so its socket gets closed");
        assertFalse(session.lifecycle().isClosed());
        assertEquals(ServerPhase.RESERVED, session.phase());
    }

    @Test
    void commandChannelAtTheHandshakeDeadlineIsRefused() {
        ServerSession session = newSession();
        PendingConnection pc = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(pc, cmdChannel(pc, new Sink(null)), s(3))); // acceptedAt + 3 s

        assertTrue(pc.claimForClose());
        assertFalse(session.lifecycle().isClosed(), "the handler decides what to do with the session");
        assertEquals(ServerPhase.RESERVED, session.phase());
    }

    @Test
    void commandChannelOnAClosedSessionIsRefused() {
        ServerSession session = newSession();
        session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "closed first", false));
        PendingConnection pc = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(pc, cmdChannel(pc, new Sink(null)), 0));

        assertTrue(pc.claimForClose());
        assertEquals("closed first", closeInfo(session).message());
    }

    // ---- packets the phase table forbids -----------------------------------------------------

    @Test
    void packetTypeOutsideTheCommandTableClosesWithProtocolError() throws Exception {
        Sink sink = new Sink(null);
        ServerSession session = reserved(sink);
        session.dispatch(Packet.ping(1));
        assertEquals(List.of(PacketType.PONG), awaitWritten(sink, 1)); // the writer is running

        session.dispatch(Packet.mouseMove(1, 1)); // the Client never sends input to the Server

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected MOUSE_MOVE", info.message());
        assertTrue(info.sendDisconnect());
        assertEquals(List.of(PacketType.PONG, PacketType.DISCONNECT), awaitWritten(sink, 2));
    }

    @Test
    void secondScreenInfoIsAProtocolViolationAndKeepsTheFirstSize() {
        ServerSession session = reserved(new Sink(null));
        session.dispatch(Packet.screenInfo(1920, 1080));
        assertFalse(session.lifecycle().isClosed());

        session.dispatch(Packet.screenInfo(1280, 720));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SCREEN_INFO", info.message());
        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow());
    }

    @Test
    void screenInfoWhileActiveIsAProtocolViolation() {
        ServerSession session = active(new Sink(null));

        session.dispatch(Packet.screenInfo(1280, 720));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SCREEN_INFO", info.message());
        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow());
    }

    @Test
    void pingBeforeTheCommandChannelExistsIsIgnored() {
        ServerSession session = newSession(); // RESERVED, no writer, no heartbeat

        session.dispatch(Packet.ping(1));

        assertFalse(session.lifecycle().isClosed());
        assertEquals(ServerPhase.RESERVED, session.phase());
        assertTrue(session.rttNanos().isEmpty());
    }

    @Test
    void pongAnswerThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ServerSession session = reserved(sink);
        fillWithPongs(session, sink);

        session.dispatch(Packet.ping(999)); // the 258th answer: one in the writer, 256 queued, no room

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals("command queue overflow", info.message());
        assertTrue(info.sendDisconnect());
    }

    // ---- activation --------------------------------------------------------------------------

    @Test
    void screenInfoAfterTheReserveWindowDoesNotActivate() {
        ServerSession session = reserved(new Sink(null));
        attachImage(session, 0); // image channel first, inside the window
        assertEquals(ServerPhase.RESERVED, session.phase());

        clock.set(s(5)); // reservedAt 0 + reserve 5 s
        session.dispatch(Packet.screenInfo(1920, 1080));

        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow(), "the size is still stored");
        assertEquals(ServerPhase.RESERVED, session.phase(), "but the window is over: no activation");
        assertTrue(session.activeAtNanos().isEmpty());
        session.tick(s(5)); // the ticker then closes it for what it is
        assertEquals("attach timeout", closeInfo(session).message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(session).reason());
    }

    @Test
    void activationThatDoesNotFitInTheQueueClosesWithOverflowAndNeverActivates() throws Exception {
        Sink sink = gatedSink();
        ServerSession session = reserved(sink);
        fillWithPongs(session, sink);
        session.dispatch(Packet.screenInfo(1920, 1080));

        attachImage(session, 0); // both conditions are met; SESSION_ACTIVE has no room

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals("command queue overflow", info.message());
        assertEquals(ServerPhase.CLOSING, session.phase());
        assertTrue(session.activeAtNanos().isEmpty(), "ACTIVE was never announced");
    }

    @Test
    void epochAndActiveInstantAreEmptyUntilActive() {
        ServerSession session = reserved(new Sink(null));
        assertEquals(0, session.inputEpoch());
        assertTrue(session.activeAtNanos().isEmpty());

        clock.set(s(2));
        session.dispatch(Packet.screenInfo(1920, 1080));
        attachImage(session, s(2));

        assertEquals(ServerPhase.ACTIVE, session.phase());
        assertEquals(s(2), session.activeAtNanos().orElseThrow());
        assertEquals(0, session.inputEpoch(), "ACTIVE with no stall yet is still revision 0");
    }

    // ---- frames ---------------------------------------------------------------------------------

    @Test
    void frameOfAnotherGenerationIsDropped() {
        ServerSession session = active(new Sink(null));

        session.publishFrame(2, image(), s(1)); // the session is generation 1

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
        assertFalse(session.controlEnabled(), "a dropped frame cannot open the gate");
    }

    @Test
    void frameBeforeActiveIsDropped() {
        ServerSession session = reserved(new Sink(null));

        session.publishFrame(1, image(), s(1));

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
        assertEquals(ServerPhase.RESERVED, session.phase());
    }

    @Test
    void frameAfterCloseIsDropped() {
        ServerSession session = active(new Sink(null));
        session.disconnect();

        session.publishFrame(1, image(), s(1));

        assertEquals(0, session.framesReceived());
        assertTrue(session.latestFrame().isEmpty());
    }

    @Test
    void tickOnAClosedActiveSessionDecidesNothingMore() {
        ServerSession session = active(new Sink(null));
        session.disconnect();
        CloseInfo first = closeInfo(session);

        session.tick(s(100)); // far past the frame timeout, but the session is already closing

        assertSame(first, closeInfo(session), "the first close reason stays");
        assertEquals(Packet.DisconnectReason.USER, first.reason());
        assertEquals(0, session.releaseAllSent());
        assertEquals(0, session.inputEpoch(), "no stall or timeout transition was recorded");
    }

    // ---- overflow on the paths that queue input -------------------------------------------------

    @Test
    void stallReleaseThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ServerSession session = active(sink);
        session.publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        session.tick(s(4)); // 3 s without a frame: the stall decision must queue RELEASE_ALL

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals(0, session.releaseAllSent(), "the RELEASE_ALL was not accepted");
    }

    @Test
    void inputThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ServerSession session = active(sink);
        session.publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        assertEquals(CommandWriter.OfferResult.OVERFLOW, session.offerInput(Packet.keyPress(999)));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals("command queue overflow", info.message());
    }

    @Test
    void epochCheckedInputThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        ServerSession session = active(sink);
        session.publishFrame(1, image(), s(1));
        awaitEntered(sink);
        fillWithInput(session);

        assertEquals(InputOutcome.OVERFLOW, session.offerInput(0, Packet.keyPress(999)));

        assertEquals(Packet.DisconnectReason.OVERFLOW, closeInfo(session).reason());
    }

    @Test
    void releaseControlInputOnAClosedSessionReturnsClosed() {
        ServerSession session = active(new Sink(null));
        session.disconnect();

        assertEquals(CommandWriter.OfferResult.CLOSED, session.releaseControlInput());

        assertEquals(0, session.controlReleasesSent());
    }

    // ---- reading what the writer produced ------------------------------------------------------

    /** Waits for the writer's flushes (no polling) until at least {@code count} packets are on the wire. */
    private static List<PacketType> awaitWritten(Sink sink, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (true) {
            int seen = sink.flushCount();
            List<PacketType> types = writtenTypes(sink);
            if (types.size() >= count) return types;
            assertTrue(sink.awaitFlushAfter(seen, deadline), "timed out waiting for " + count + " packets");
        }
    }

    private static List<PacketType> writtenTypes(Sink sink) throws IOException {
        List<PacketType> types = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(sink.snapshot()));
        while (true) {
            var p = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class));
            if (p.isEmpty()) return types;
            types.add(p.get().type());
        }
    }
}
