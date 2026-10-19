package com.ltm.rd.common;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class HeartbeatTest {
    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final Duration LIVENESS = Duration.ofSeconds(8);

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CommandWriter writer = new CommandWriter(new DataOutputStream(bytes), t -> {});
    private final AtomicInteger timeouts = new AtomicInteger();

    @AfterEach
    void cleanup() {
        writer.stop();
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
}
