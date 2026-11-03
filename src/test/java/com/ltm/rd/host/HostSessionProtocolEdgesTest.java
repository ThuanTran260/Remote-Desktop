package com.ltm.rd.host;

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
import com.ltm.rd.host.AdapterFactory;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputDevice;
import com.ltm.rd.host.ScreenSource;
import com.ltm.rd.viewer.InputOutcome;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The defensive branches of {@link HostSession}: packets the table of spec 2.4 forbids, a hand-over
 * the session must refuse, frames that must be dropped, and every path where a full command queue
 * closes the session with OVERFLOW. Each ordering is forced with the fake clock, direct calls to the
 * session and a gate inside the writer's sink, so nothing depends on thread scheduling.
 */
@Timeout(120)
class HostSessionProtocolEdgesTest {
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
    private final AtomicLong generations = new AtomicLong();
    private final List<HostSession> sessions = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final Map<HostSession, CountDownLatch> prepareGates = new IdentityHashMap<>();
    private final Map<HostSession, AtomicLong> activationTimes = new IdentityHashMap<>();
    private final Map<HostSession, CountDownLatch> geometryRead = new IdentityHashMap<>();
    private final Map<HostSession, CountDownLatch> prepareStarted = new IdentityHashMap<>();


    private final com.ltm.rd.host.HostTestAccess.HostResources hostResources = new com.ltm.rd.host.HostTestAccess.HostResources();

    @AfterEach
    void shutdown() {
        gates.forEach(CountDownLatch::countDown);
        for (HostSession session : sessions) {
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "done", false));
        }
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        hostResources.close();
    }

    // ---- fixtures --------------------------------------------------------------------------

    @Test void prepareAtReserveDeadlineWithoutTickClosesTimeout() {
        HostSession session = newSession();
        clock.set(s(5));
        PendingConnection pc = new PendingConnection(new Socket(), s(5));
        assertTrue(session.attachCommandChannel(pc, cmdChannel(pc, new Sink(null)), s(5)));
        assertTrue(session.closeInfo().isPresent(), "the reserve deadline must close without a ticker");
        assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(session).reason());
        assertEquals("attach timeout", closeInfo(session).message());
        assertTrue(session.screen().isEmpty());
    }

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

    private HostSession newSession() {
        return newSession(clock);
    }

    private HostSession newSession(LongSupplier sessionClock) {
        SessionLifecycle<HostPhase> lifecycle = new SessionLifecycle<>(HostPhase.RESERVED, HostPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        CountDownLatch prepareGate = new CountDownLatch(1);
        gates.add(prepareGate);
        AtomicLong activationTime = new AtomicLong(Long.MIN_VALUE);
        CountDownLatch geometryReady = new CountDownLatch(1);
        CountDownLatch preparing = new CountDownLatch(1);
        AdapterFactory adapters = new AdapterFactory() {
            @Override public void refreshInputDevice() {
                preparing.countDown();
                try { prepareGate.await(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test adapter interrupted", e);
                }
            }
            @Override public ScreenSource newScreenSource() {
                FakeScreenSource delegate = new FakeScreenSource(1920, 1080);
                return new ScreenSource() {
                    @Override public com.ltm.rd.host.ScreenGeometry geometry() {
                        geometryReady.countDown();
                        return delegate.geometry();
                    }
                    @Override public BufferedImage capture(java.awt.Rectangle bounds) throws IOException { return delegate.capture(bounds); }
                };
            }
            @Override public InputDevice inputDevice() { throw new AssertionError("session borrows input"); }
        };
        HostSession.Env env = new HostSession.Env(TIMEOUTS, sessionClock, null, () -> false, null, null,
                hostResources.input, adapters, geometry -> activationTime.set(sessionClock.getAsLong()), () -> { });
        HostSession session = new HostSession(generations.incrementAndGet(), TOKEN,
                InetAddress.getLoopbackAddress(), 0, lifecycle, env);
        sessions.add(session);
        prepareGates.put(session, prepareGate);
        activationTimes.put(session, activationTime);
        geometryRead.put(session, geometryReady);
        prepareStarted.put(session, preparing);
        return session;
    }

    private static Channel cmdChannel(PendingConnection pc, Sink sink) {
        return new Channel(pc.socket(), new DataInputStream(new IdleInput()), new DataOutputStream(sink));
    }

    /** RESERVED, command channel attached at time 0, no SCREEN_INFO and no image channel. */
    private HostSession reserved(Sink sink) {
        HostSession session = newSession();
        PendingConnection pc = new PendingConnection(new Socket(), 0);
        assertTrue(session.attachCommandChannel(pc, cmdChannel(pc, sink), 0));
        return session;
    }

    private static void attachImage(HostSession session, long now) {
        PendingConnection pc = new PendingConnection(new Socket(), now);
        Channel img = new Channel(pc.socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        session.attachImage(Packet.attach(TOKEN).attachPayload(), pc, img, now);
    }

    private HostSession active(Sink sink) {
        HostSession session = reserved(sink);
        releasePrepare(session);
        attachImage(session, 0);
        awaitHost(() -> session.phase() == HostPhase.ACTIVE || session.closeInfo().isPresent());
        assertEquals(HostPhase.ACTIVE, session.phase());
        return session;
    }

    private static BufferedImage image() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    private static void awaitEntered(Sink sink) throws InterruptedException {
        assertTrue(sink.entered.await(WAIT_S, TimeUnit.SECONDS), "the writer is stuck inside its first write");
    }

    /** Lets the first PONG into the writer (which then blocks) and queues 256 more, so the queue is full. */
    private static void fillWithPongs(HostSession session, Sink sink) throws InterruptedException {
        session.dispatch(Packet.ping(1));
        awaitEntered(sink);
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) session.dispatch(Packet.ping(i + 2));
        assertFalse(session.lifecycle().isClosed(), "a queue that is exactly full is not an overflow yet");
    }

    private static CloseInfo closeInfo(HostSession session) {
        return session.closeInfo().orElseThrow();
    }

    private static void awaitLatch(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS), what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(what + " (interrupted)");
        }
    }

    /**
     * The reserve window was over when activation was decided: the session is not ACTIVE, and the tick
     * closes it as it always did, with TIMEOUT "attach timeout". The wire shows the PONG that proves
     * the writer is running, then DISCONNECT: SESSION_ACTIVE was never queued.
     */
    private void assertStaysReservedUntilTheAttachTimeout(HostSession session, Sink sink, long tickAt)
            throws Exception {
        assertEquals(HostPhase.CLOSING, session.phase());
        assertTrue(activationTime(session).isEmpty());
        session.tick(tickAt);
        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.TIMEOUT, info.reason());
        assertEquals("attach timeout", info.message());
        assertTrue(info.sendDisconnect());
        assertFalse(writtenTypes(sink).contains(PacketType.SESSION_ACTIVE));
        assertEquals(0, session.inputBeginCount());
        assertEquals(0, session.senderRuns());
    }

    // ---- command channel hand-over ---------------------------------------------------------

    @Test
    void secondCommandChannelIsRefusedAndTheFirstKeepsRunning() {
        HostSession session = reserved(new Sink(null));
        PendingConnection second = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(second, cmdChannel(second, new Sink(null)), 0));

        assertTrue(second.claimForClose(), "a refused connection stays claimable so its socket gets closed");
        assertFalse(session.lifecycle().isClosed());
        assertEquals(HostPhase.RESERVED, session.phase());
    }

    @Test
    void commandChannelAtTheHandshakeDeadlineIsRefused() {
        HostSession session = newSession();
        PendingConnection pc = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(pc, cmdChannel(pc, new Sink(null)), s(3))); // acceptedAt + 3 s

        assertTrue(pc.claimForClose());
        assertFalse(session.lifecycle().isClosed(), "the handler decides what to do with the session");
        assertEquals(HostPhase.RESERVED, session.phase());
    }

    @Test
    void commandChannelOnAClosedSessionIsRefused() {
        HostSession session = newSession();
        session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "closed first", false));
        PendingConnection pc = new PendingConnection(new Socket(), 0);

        assertFalse(session.attachCommandChannel(pc, cmdChannel(pc, new Sink(null)), 0));

        assertTrue(pc.claimForClose());
        assertEquals("closed first", closeInfo(session).message());
    }

    // ---- packets the phase table forbids -----------------------------------------------------

    @Test
    void secondScreenInfoInReservedPassesRulesButClosesSession() {
        HostSession session = reserved(new Sink(null));
        releasePrepare(session);
        assertEquals(HostPhase.RESERVED, session.phase());

        session.dispatch(Packet.screenInfo(1280, 720));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SCREEN_INFO", info.message());
        assertTrue(info.sendDisconnect());
        assertTrue(activationTime(session).isEmpty());
        assertEquals(HostPhase.CLOSING, session.phase());
        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow());
    }

    @Test
    void neverValidCommandTypeIsRejectedByTheReader() throws Exception {
        HostSession session = newSession();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PacketIO.write(new DataOutputStream(bytes), Packet.frame(16, 9, new byte[10]));
        PendingConnection pc = new PendingConnection(new Socket(), 0);
        Channel channel = new Channel(pc.socket(), new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                new DataOutputStream(new Sink(null)));
        assertTrue(session.attachCommandChannel(pc, channel, 0));

        session.terminated().get(WAIT_S, TimeUnit.SECONDS);

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("protocol violation", info.message());
        assertTrue(info.sendDisconnect());
    }

    @Test
    void packetTypeOutsideTheCommandTableClosesWithProtocolError() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = reserved(sink);
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
        HostSession session = reserved(new Sink(null));
        releasePrepare(session);
        assertFalse(session.lifecycle().isClosed());

        session.dispatch(Packet.screenInfo(1280, 720));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SCREEN_INFO", info.message());
        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow());
    }

    @Test
    void screenInfoWhileActiveIsAProtocolViolation() {
        HostSession session = active(new Sink(null));

        session.dispatch(Packet.screenInfo(1280, 720));

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("unexpected SCREEN_INFO", info.message());
        assertEquals(new Packet.SizePayload(1920, 1080), session.screen().orElseThrow());
    }

    @Test
    void pingBeforeTheCommandChannelExistsIsIgnored() {
        HostSession session = newSession(); // RESERVED, no writer, no heartbeat

        session.dispatch(Packet.ping(1));

        assertFalse(session.lifecycle().isClosed());
        assertEquals(HostPhase.RESERVED, session.phase());
        assertTrue(session.rttNanos().isEmpty());
    }

    @Test
    void pongAnswerThatDoesNotFitClosesWithOverflow() throws Exception {
        Sink sink = gatedSink();
        HostSession session = reserved(sink);
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
        HostSession session = reserved(new Sink(null));
        attachImage(session, 0); // image channel first, inside the window
        assertEquals(HostPhase.RESERVED, session.phase());

        clock.set(s(5)); // reservedAt 0 + reserve 5 s
        releasePrepare(session);

        assertTrue(session.screen().isEmpty(), "late geometry must be discarded");
        assertEquals(HostPhase.CLOSING, session.phase(), "the reserve deadline wins before the next tick");
        assertTrue(activationTime(session).isEmpty());
        session.tick(s(5)); // the ticker then closes it for what it is
        assertEquals("attach timeout", closeInfo(session).message());
        assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(session).reason());
    }

    /**
     * SCREEN_INFO's dispatcher has already read its packet timestamp (inside the reserve window) and
     * waits for the session lock; the window ends while another thread holds the lock. Activation must
     * be decided on the time read under the lock, not on the stale packet timestamp.
     */
    @Test
    void screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = reserved(sink);
        attachImage(session, 0);
        awaitLatch(prepareStarted.get(session), "prepare entered its adapter before the lifecycle lock is held");
        session.lifecycle().atomically(tx -> {
            prepareGates.get(session).countDown();
            awaitLatch(geometryRead.get(session), "geometry is read outside the lifecycle lock");
            clock.set(s(6));
            return null;
        });
        awaitHost(() -> session.closeInfo().isPresent());
        assertTrue(session.screen().isEmpty(), "geometry prepared before the lock is stale at publication");
        assertStaysReservedUntilTheAttachTimeout(session, sink, s(6));
    }

    @Test
    void screenInfoOneNanosecondBeforeTheReserveDeadlineActivatesAtThatInstant() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = reserved(sink);
        attachImage(session, 0);

        clock.set(s(5) - 1); // reservedAt 0 + reserve 5 s, minus one nanosecond
        releasePrepare(session);

        assertEquals(HostPhase.ACTIVE, session.phase());
        assertEquals(s(5) - 1, activationTime(session).orElseThrow());
        assertEquals(List.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE), awaitWritten(sink, 2));
    }

    @Test
    void imageAttachCarryingAStaleTimestampCannotActivateAtTheReserveDeadline() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = reserved(sink);
        releasePrepare(session);

        clock.set(s(5)); // exactly reservedAt 0 + reserve 5 s: equality means expired
        attachImage(session, 0); // admitted on the caller's timestamp from inside the window

        assertStaysReservedUntilTheAttachTimeout(session, sink, s(5));
    }

    @Test
    void commandChannelAttachCarryingAStaleTimestampCannotActivateAtTheReserveDeadline() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = newSession();
        prepareGates.get(session).countDown(); // preparation cannot start before the command handover
        attachImage(session, 0);

        clock.set(s(5)); // exactly reservedAt 0 + reserve 5 s: equality means expired
        PendingConnection pc = new PendingConnection(new Socket(), 0);
        assertTrue(session.attachCommandChannel(pc, cmdChannel(pc, sink), 0));

        assertStaysReservedUntilTheAttachTimeout(session, sink, s(5));
    }

    @Test
    void imageAttachActivatesAtTheClockReadInsideTheTransactionNotAtItsCallersTimestamp() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = reserved(sink);
        clock.set(s(1));
        releasePrepare(session);

        clock.set(s(2));
        attachImage(session, s(1)); // the caller read s(1) before it waited for the lock

        assertEquals(HostPhase.ACTIVE, session.phase());
        assertEquals(s(2), activationTime(session).orElseThrow());
        assertEquals(List.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE), awaitWritten(sink, 2));
    }

    @Test
    void commandChannelAttachActivatesAtTheClockReadInsideTheTransactionNotAtItsCallersTimestamp() throws Exception {
        Sink sink = new Sink(null);
        HostSession session = newSession();
        clock.set(s(1));
        prepareGates.get(session).countDown();
        attachImage(session, s(1));

        clock.set(s(2));
        PendingConnection pc = new PendingConnection(new Socket(), s(1));
        assertTrue(session.attachCommandChannel(pc, cmdChannel(pc, sink), s(1))); // read before waiting for the lock

        awaitHost(() -> session.phase() == HostPhase.ACTIVE);
        assertEquals(HostPhase.ACTIVE, session.phase());
        assertEquals(s(2), activationTime(session).orElseThrow());
        assertEquals(List.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE), awaitWritten(sink, 2));
    }

    @Test
    void activationThatDoesNotFitInTheQueueClosesWithOverflowAndNeverActivates() throws Exception {
        Sink sink = gatedSink();
        HostSession session = reserved(sink);
        releasePrepare(session);
        awaitEntered(sink); // SCREEN_INFO is the in-flight first write
        for (int i = 0; i < CommandWriter.DEFAULT_CAPACITY; i++) session.dispatch(Packet.ping(i + 1));

        attachImage(session, 0); // both conditions are met; SESSION_ACTIVE has no room

        CloseInfo info = closeInfo(session);
        assertEquals(Packet.DisconnectReason.OVERFLOW, info.reason());
        assertEquals("command queue overflow", info.message());
        assertEquals(HostPhase.CLOSING, session.phase());
        assertEquals(1, session.inputBeginCount(), "begin precedes the activation offer");
        assertEquals(0, session.senderRuns());
        session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
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

    private void releasePrepare(HostSession session) {
        prepareGates.get(session).countDown();
        awaitHost(() -> session.screen().isPresent() || session.closeInfo().isPresent());
    }

    private OptionalLong activationTime(HostSession session) {
        long value = activationTimes.get(session).get();
        return value == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static void awaitHost(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Host worker did not finish its transaction");
            LockSupport.parkNanos(1_000_000);
        }
    }

    @Test void refusedInputSessionNeverSendsActivation() throws Exception {
        assertTrue(hostResources.input.beginSession(1_000_000), "another session already owns the input");
        try {
            Sink sink = new Sink(null);
            HostSession session = reserved(sink);
            releasePrepare(session);
            attachImage(session, 0);
            session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
            CloseInfo info = closeInfo(session);
            assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
            assertEquals("input session refused", info.message());
            assertTrue(info.sendDisconnect());
            assertEquals(HostPhase.CLOSING, session.phase());
            assertEquals(0, session.inputBeginCount());
            assertEquals(0, session.senderRuns());
            session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
            List<PacketType> types = writtenTypes(sink);
            assertFalse(types.contains(PacketType.SESSION_ACTIVE));
            assertEquals(PacketType.DISCONNECT, types.get(types.size() - 1));
        } finally {
            hostResources.input.endSession(1_000_000).get(WAIT_S, TimeUnit.SECONDS);
        }
    }

    @RepeatedTest(20)
    void closeRacingPrepareStartSkipsOrJoinsWorker() throws Exception {
        for (boolean bodyEntered : new boolean[] {false, true}) {
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            gates.add(release);
            java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger sources = new java.util.concurrent.atomic.AtomicInteger();
            hostResources.factory = new AdapterFactory() {
                @Override public InputDevice inputDevice() { throw new AssertionError(); }
                @Override public void refreshInputDevice() {
                    refreshes.incrementAndGet();
                    if (bodyEntered) { reached.countDown(); awaitGate(release); }
                }
                @Override public ScreenSource newScreenSource() {
                    sources.incrementAndGet();
                    return new FakeScreenSource(1920, 1080);
                }
            };
            java.util.concurrent.ThreadFactory threads = runnable -> new Thread(() -> {
                if (!bodyEntered && Thread.currentThread().getName().equals("rd-host-prepare")) {
                    reached.countDown();
                    awaitGate(release);
                }
                runnable.run();
            });
            HostCore core = hostResources.core(new HostConfig(InetAddress.getLoopbackAddress(), 0, 0,
                    TIMEOUTS, clock, new java.security.SecureRandom()), new HostCore.Seams(threads, null, null, false));
            core.start();
            try (com.ltm.rd.it.RawPeer peer = com.ltm.rd.it.RawPeer.connect(core.cmdPort())) {
                String code = core.sessions().currentCode();
                peer.send(Packet.auth(code));
                assertEquals(Packet.AuthStatus.OK, peer.read().authResultPayload().status());
                assertTrue(reached.await(WAIT_S, TimeUnit.SECONDS));
                HostSession session = core.sessions().current().orElseThrow();
                CloseInfo first = new CloseInfo(Packet.DisconnectReason.USER, "close won", false);
                assertTrue(session.close(first));
                session.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
                assertFalse(session.terminated().isDone());
                assertSame(session, core.sessions().current().orElseThrow());
                assertEquals(code, core.sessions().currentCode());
                try (com.ltm.rd.it.RawPeer busy = com.ltm.rd.it.RawPeer.connect(core.cmdPort())) {
                    busy.send(Packet.auth(code));
                    assertEquals(Packet.AuthStatus.BUSY, busy.read().authResultPayload().status());
                }
                release.countDown();
                session.terminated().get(WAIT_S, TimeUnit.SECONDS);
                session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
                awaitHost(() -> core.sessions().current().isEmpty());
                assertSame(first, session.closeInfo().orElseThrow());
                assertEquals(bodyEntered ? 1 : 0, refreshes.get());
                assertEquals(bodyEntered ? 1 : 0, sources.get());
                assertTrue(session.screen().isEmpty());
                assertEquals(0, session.inputBeginCount());
                assertEquals(0, session.senderRuns());
            } finally { release.countDown(); core.close(); }
            assertEquals(0, core.tickerRegistrations());
        }
        hostResources.factory = null;
    }

    private static void awaitGate(CountDownLatch gate) {
        boolean interrupted = false;
        while (gate.getCount() != 0) {
            try { gate.await(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @RepeatedTest(20)
    void reserveDeadlineRacingPrepareDiscardsExpiredResult() throws Exception {
        for (boolean expired : new boolean[] {false, true}) {
            clock.set(0);
            Sink sink = new Sink(null);
            HostSession session = reserved(sink);
            attachImage(session, 0);
            clock.set(s(5) - (expired ? 0 : 1));
            releasePrepare(session);
            if (expired) {
                assertEquals(Packet.DisconnectReason.TIMEOUT, closeInfo(session).reason());
                assertEquals("attach timeout", closeInfo(session).message());
                assertTrue(session.screen().isEmpty());
                assertTrue(activationTime(session).isEmpty());
                assertEquals(0, session.inputBeginCount());
                assertFalse(writtenTypes(sink).contains(PacketType.SESSION_ACTIVE));
            } else {
                assertEquals(HostPhase.ACTIVE, session.phase());
                assertEquals(1, session.inputBeginCount());
                assertEquals(List.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE), awaitWritten(sink, 2));
                session.disconnect();
            }
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
            session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        }
    }

    @RepeatedTest(20)
    void attachRacingPrepareActivatesExactlyOnce() throws Exception {
        assertPrepareAndAttachInEitherOrder();
    }

    @RepeatedTest(20)
    void prepareAndAttachInEitherOrderActivateOnce() throws Exception {
        assertPrepareAndAttachInEitherOrder();
    }

    private void assertPrepareAndAttachInEitherOrder() throws Exception {
        for (boolean attachFirst : new boolean[] {true, false}) {
            clock.set(0);
            Sink sink = new Sink(null);
            HostSession session = reserved(sink);
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            CountDownLatch first = new CountDownLatch(1);
            ExecutorService triggers = Executors.newFixedThreadPool(2);
            try {
                Future<?> attach = triggers.submit(() -> {
                    start.await(WAIT_S, TimeUnit.SECONDS);
                    if (!attachFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                    attachImage(session, 0);
                    if (attachFirst) { assertEquals(HostPhase.RESERVED, session.phase()); first.countDown(); }
                    return null;
                });
                Future<?> prepare = triggers.submit(() -> {
                    start.await(WAIT_S, TimeUnit.SECONDS);
                    if (attachFirst) assertTrue(first.await(WAIT_S, TimeUnit.SECONDS));
                    releasePrepare(session);
                    if (!attachFirst) { assertEquals(HostPhase.RESERVED, session.phase()); first.countDown(); }
                    return null;
                });
                attach.get(WAIT_S, TimeUnit.SECONDS);
                prepare.get(WAIT_S, TimeUnit.SECONDS);
                assertEquals(HostPhase.ACTIVE, session.phase());
                awaitHost(() -> session.senderRuns() == 1);
                assertEquals(1, session.inputBeginCount());
                assertEquals(1, session.senderRuns());
                assertEquals(List.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE), awaitWritten(sink, 2));
            } finally { first.countDown(); triggers.shutdownNow(); session.disconnect(); }
            session.terminated().get(WAIT_S, TimeUnit.SECONDS);
            session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
        }
    }

    private static Packet packet(com.ltm.rd.common.PacketType type) {
        return switch (type) {
            case AUTH -> Packet.auth("123456");
            case AUTH_RESULT -> Packet.authResult(Packet.AuthStatus.OK, new byte[16]);
            case ATTACH -> Packet.attach(new byte[16]);
            case SCREEN_INFO -> Packet.screenInfo(1920, 1080);
            case SESSION_ACTIVE -> Packet.sessionActive();
            case FRAME -> Packet.frame(1, 1, new byte[] {1});
            case MOUSE_MOVE -> Packet.mouseMove(1, 1);
            case MOUSE_PRESS -> Packet.mousePress(1, 1, 1);
            case MOUSE_RELEASE -> Packet.mouseRelease(1, 1, 1);
            case WHEEL -> Packet.wheel(1);
            case KEY_PRESS -> Packet.keyPress(65);
            case KEY_RELEASE -> Packet.keyRelease(65);
            case RELEASE_ALL -> Packet.releaseAll();
            case PING -> Packet.ping(1);
            case PONG -> Packet.pong(1);
            case DISCONNECT -> Packet.disconnect(Packet.DisconnectReason.USER, "test");
        };
    }

    @Test void listenerRejectsEveryDisallowedCommandType() throws Exception {
        var pending = EnumSet.of(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT);
        var active = EnumSet.of(PacketType.MOUSE_MOVE, PacketType.MOUSE_PRESS, PacketType.MOUSE_RELEASE,
                PacketType.WHEEL, PacketType.KEY_PRESS, PacketType.KEY_RELEASE, PacketType.RELEASE_ALL,
                PacketType.PING, PacketType.PONG, PacketType.DISCONNECT);
        for (boolean activated : new boolean[] {false, true}) {
            for (PacketType type : PacketType.values()) {
                if ((activated ? active : pending).contains(type)) continue;
                clock.set(0);
                HostSession session = activated ? active(new Sink(null)) : reserved(new Sink(null));
                session.dispatch(packet(type));
                assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, session.closeInfo().orElseThrow().reason(), type.name());
                assertEquals(HostPhase.CLOSING, session.phase());
                session.terminated().get(WAIT_S, TimeUnit.SECONDS);
                session.inputReleased().get(WAIT_S, TimeUnit.SECONDS);
            }
        }
    }
}
