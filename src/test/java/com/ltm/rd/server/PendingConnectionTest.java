package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class PendingConnectionTest {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private static final int WAIT_S = 10;

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
    void shutdown() {
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private SessionLifecycle<ServerPhase> lifecycle() {
        return new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING, System::nanoTime,
                Duration.ofMillis(50), scheduler, cleanup);
    }

    private static boolean handOver(SessionLifecycle<ServerPhase> lc, PendingConnection pc, long now) {
        return lc.atomically(tx -> pc.handOver(now, DEADLINE, tx));
    }

    @Test
    void onlyOneOfHandOverAndClaimForCloseWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            for (int i = 0; i < 1000; i++) {
                PendingConnection pc = new PendingConnection(new Socket(), 0);
                SessionLifecycle<ServerPhase> lc = lifecycle();
                Future<Boolean> handed = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return handOver(lc, pc, s(1));
                });
                Future<Boolean> claimed = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return pc.claimForClose();
                });
                boolean h = handed.get(WAIT_S, TimeUnit.SECONDS);
                boolean c = claimed.get(WAIT_S, TimeUnit.SECONDS);
                assertTrue(h ^ c, "iteration " + i + ": handOver=" + h + " claim=" + c);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void forcedOrderingsAreExclusive() throws Exception {
        PendingConnection claimedFirst = new PendingConnection(new Socket(), 0);
        assertTrue(claimedFirst.claimForClose());
        assertFalse(handOver(lifecycle(), claimedFirst, s(1)), "claimed connections cannot be handed over");
        assertFalse(claimedFirst.claimForClose(), "claim is exclusive");

        PendingConnection handedFirst = new PendingConnection(new Socket(), 0);
        assertTrue(handOver(lifecycle(), handedFirst, s(1)));
        assertFalse(handedFirst.claimForClose(), "handed-over connections belong to the session");
        assertFalse(handOver(lifecycle(), handedFirst, s(1)), "no second hand-over");
    }

    @Test
    void handOverRefusedAtAbsoluteDeadline() throws Exception {
        PendingConnection early = new PendingConnection(new Socket(), 0);
        assertTrue(handOver(lifecycle(), early, s(3) - 1));

        PendingConnection late = new PendingConnection(new Socket(), 0);
        assertFalse(handOver(lifecycle(), late, s(3)));
        assertTrue(late.claimForClose(), "a refused hand-over leaves the connection claimable");
    }

    @Test
    void handOverToClosedLifecycleFails() {
        SessionLifecycle<ServerPhase> lc = lifecycle();
        lc.close(new CloseInfo(Packet.DisconnectReason.USER, "closed", false));
        PendingConnection pc = new PendingConnection(new Socket(), 0);
        assertFalse(handOver(lc, pc, s(1)));
        assertTrue(pc.claimForClose(), "still pending, so the handler can close it");
    }

    @Test
    void handOverRegistersSocketWithLifecycle() throws Exception {
        SessionLifecycle<ServerPhase> lc = lifecycle();
        Socket socket = new Socket();
        PendingConnection pc = new PendingConnection(socket, 0);
        assertTrue(handOver(lc, pc, s(1)));
        lc.close(new CloseInfo(Packet.DisconnectReason.USER, "x", false));
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(socket.isClosed());
    }

    @Test
    void tickClosesExpiredOnly() throws Exception {
        PendingConnections pending = new PendingConnections(DEADLINE);
        Socket old = new Socket();
        Socket fresh = new Socket();
        Socket owned = new Socket();
        PendingConnection oldPc = new PendingConnection(old, 0);
        PendingConnection freshPc = new PendingConnection(fresh, s(2));
        PendingConnection ownedPc = new PendingConnection(owned, 0);
        pending.add(oldPc);
        pending.add(freshPc);
        pending.add(ownedPc);
        assertTrue(handOver(lifecycle(), ownedPc, s(1)));

        pending.tick(s(3) - 1);
        assertFalse(old.isClosed(), "never closes before the deadline");
        pending.tick(s(3));
        assertTrue(old.isClosed());
        assertFalse(fresh.isClosed(), "accepted at 2 s, expires at 5 s");
        assertFalse(owned.isClosed(), "handed-over sockets are the session's now");

        pending.tick(s(5));
        assertTrue(fresh.isClosed());
        assertFalse(owned.isClosed());
    }

    @Test
    void removedConnectionsAreNotClosedByTickAndCloseAllClosesPendingOnes() throws Exception {
        PendingConnections pending = new PendingConnections(DEADLINE);
        Socket removed = new Socket();
        Socket kept = new Socket();
        PendingConnection removedPc = new PendingConnection(removed, 0);
        PendingConnection keptPc = new PendingConnection(kept, 0);
        pending.add(removedPc);
        pending.add(keptPc);
        pending.remove(removedPc);
        pending.tick(s(10));
        assertFalse(removed.isClosed());
        assertTrue(kept.isClosed());

        Socket another = new Socket();
        pending.add(new PendingConnection(another, 0));
        pending.closeAll();
        assertTrue(another.isClosed());
    }
}
