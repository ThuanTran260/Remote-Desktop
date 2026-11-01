package com.ltm.rd.common;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.ClientPhase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class HeartbeatTest {
    private static final int WAIT_S = 10;
    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final Duration LIVENESS = Duration.ofSeconds(8);

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CommandWriter writer = new CommandWriter(new DataOutputStream(bytes), t -> {});
    private final AtomicInteger timeouts = new AtomicInteger();
    private final List<CommandWriter> writers = new ArrayList<>();
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

    @AfterEach
    void cleanup() {
        writer.stop();
        writers.forEach(CommandWriter::stop);
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private Heartbeat heartbeat() {
        return new Heartbeat(writer, INTERVAL, LIVENESS, 0, timeouts::incrementAndGet);
    }

    @Test
    void heartbeatSendsPingEveryInterval() {
        Heartbeat hb = heartbeat(); // writer not started: offered packets stay in its queue
        hb.onPacketReceived(0);
        hb.tick(0);
        hb.onPacketReceived(s(2));
        hb.tick(s(2));
        hb.onPacketReceived(s(4));
        hb.tick(s(4));
        assertEquals(List.of(Packet.ping(1), Packet.ping(2)), writer.queuedSnapshot());
        assertEquals(0, timeouts.get());
    }

    @Test
    void heartbeatBoundary() {
        Heartbeat hb = heartbeat();
        hb.tick(s(8) - 1);
        assertEquals(0, timeouts.get());
        hb.tick(s(8));
        assertEquals(1, timeouts.get());
        hb.tick(s(20));
        assertEquals(1, timeouts.get(), "timeout is reported once");
    }

    @Test
    void packetReceivedResetsLiveness() {
        Heartbeat hb = heartbeat();
        assertTrue(hb.onPacketReceived(s(7)));
        hb.tick(s(15) - 1);
        assertEquals(0, timeouts.get());
        hb.tick(s(15));
        assertEquals(1, timeouts.get());
    }

    @Test
    void packetBeforeTickAtDeadlineAvoidsTimeout() {
        Heartbeat hb = heartbeat();
        assertTrue(hb.onPacketReceived(s(8)));
        hb.tick(s(8));
        assertEquals(0, timeouts.get());
    }

    @Test
    void tickBeforePacketAtDeadlineRejectsPacket() {
        Heartbeat hb = heartbeat();
        hb.tick(s(8));
        assertFalse(hb.onPacketReceived(s(8)));
        assertEquals(1, timeouts.get());
    }

    @Test
    void packetAfterTimeoutIsRejected() {
        Heartbeat hb = heartbeat();
        hb.tick(s(8));
        assertFalse(hb.onPacketReceived(s(9)));
    }

    @Test
    void packetVersusTimeoutRaceExactlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            for (int i = 0; i < 2_000; i++) {
                AtomicInteger fired = new AtomicInteger();
                Heartbeat hb = new Heartbeat(writer, INTERVAL, LIVENESS, 0, fired::incrementAndGet);
                Future<Boolean> accepted = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return hb.onPacketReceived(s(8));
                });
                Future<?> ticked = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    hb.tick(s(8));
                    return null;
                });
                boolean ok = accepted.get(10, TimeUnit.SECONDS);
                ticked.get(10, TimeUnit.SECONDS);
                boolean timedOut = fired.get() == 1;
                assertTrue(ok ^ timedOut, "iteration " + i + ": accepted=" + ok + " timedOut=" + timedOut);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rttFromMatchingPongOnly() {
        Heartbeat hb = heartbeat();
        assertTrue(hb.lastRttNanos().isEmpty());
        hb.onPong(1, s(1)); // no ping outstanding
        assertTrue(hb.lastRttNanos().isEmpty());
        hb.tick(s(2)); // ping(1)
        hb.onPong(99, s(3)); // wrong seq
        assertTrue(hb.lastRttNanos().isEmpty());
        hb.onPong(1, s(2) + 5_000_000);
        assertEquals(5_000_000, hb.lastRttNanos().getAsLong());
    }

    private SessionLifecycle<ClientPhase> lifecycle(ClientPhase phase) {
        SessionLifecycle<ClientPhase> lc = new SessionLifecycle<>(ClientPhase.CONNECTING, ClientPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(100), scheduler, cleanup);
        lc.atomically(tx -> tx.advance(ClientPhase.CONNECTING, phase));
        return lc;
    }

    private static void answerPing(SessionLifecycle<ClientPhase> lc, CommandWriter writer, Packet p) {
        LifecyclePort<ClientPhase> port = new LifecyclePort<>(lc, 1, null, System::nanoTime,
                new LifecyclePort.Shell<>() {
                    @Override public boolean isActive(ClientPhase phase) { return phase == ClientPhase.ACTIVE; }
                    @Override public boolean isActivationPending(ClientPhase phase) { return phase == ClientPhase.ATTACHING; }
                    @Override public boolean activationExpired(long now) { return false; }
                    @Override public CommandWriter writer() { return writer; }
                    @Override public void tryActivate(SessionLifecycle.Tx<ClientPhase> tx) { }
                });
        port.atomically(stx -> { Heartbeat.answerPing(stx, port, p); return null; });
    }

    @Test
    void pongThatOverflowsTheWriterQueueClosesTheSession() {
        CommandWriter tiny = new CommandWriter(new DataOutputStream(new ByteArrayOutputStream()), 1, Thread::new,
                t -> {}, System::nanoTime);
        writers.add(tiny);
        assertEquals(CommandWriter.OfferResult.ACCEPTED, tiny.offer(Packet.ping(1))); // the queue is now full
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ATTACHING);
        answerPing(lc, tiny, Packet.ping(2));
        assertEquals(Packet.DisconnectReason.OVERFLOW, lc.closeInfo().orElseThrow().reason());
    }

    @Test
    void pingAnsweredWithPong() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                bytes.write(b);
            }

            @Override
            public void flush() {
                flushed.countDown();
            }
        };
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), t -> {});
        writers.add(w);
        w.start();
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ATTACHING);
        answerPing(lc, w, Packet.ping(42));
        assertTrue(flushed.await(WAIT_S, TimeUnit.SECONDS));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Packet reply = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class)).orElseThrow();
        assertEquals(PacketType.PONG, reply.type());
        assertEquals(42, reply.seq());
        assertFalse(lc.isClosed());
    }
}
