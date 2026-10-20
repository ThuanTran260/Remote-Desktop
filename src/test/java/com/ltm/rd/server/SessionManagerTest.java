package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet.AuthStatus;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class SessionManagerTest {
    private static final int WAIT_S = 10;

    private final FakeClock clock = new FakeClock();
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

    private static InetAddress ip(int n) throws Exception {
        return InetAddress.getByAddress(new byte[] {10, 0, 0, (byte) n});
    }

    private ServerSession newSession(long generation, byte[] token, InetAddress peer, long reservedAt) {
        return new ServerSession(generation, token, peer, reservedAt, new SessionLifecycle<>(ServerPhase.RESERVED,
                ServerPhase.CLOSING, System::nanoTime, Duration.ofMillis(100), scheduler, cleanup));
    }

    private SessionManager manager(SecureRandom random, SessionManager.SessionFactory factory) {
        return new SessionManager(random, new AuthGuard(1024, 3, Duration.ofSeconds(30)), Timeouts.DEFAULTS, clock,
                factory);
    }

    private SessionManager manager() {
        return manager(new SecureRandom(), this::newSession);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    void codeIsSixDigits() {
        assertTrue(manager().currentCode().matches("\\d{6}"));
    }

    @Test
    void correctCodeReservesAndSecondGetsBusy() throws Exception {
        SessionManager m = manager();
        ReserveResult first = m.tryReserve(ip(1), 1, m.currentCode());
        assertEquals(AuthStatus.OK, first.status());
        ServerSession session = first.session();
        assertNotNull(session);
        assertEquals(1, session.generation());
        assertEquals(16, session.token().length);
        assertEquals(ip(1), session.peer());
        assertSame(session, m.current().orElseThrow());

        byte[] copy = session.token();
        copy[0] ^= 0x55;
        assertFalse(Arrays.equals(copy, session.token()), "token() returns a defensive copy");

        ReserveResult second = m.tryReserve(ip(2), 1, m.currentCode());
        assertEquals(AuthStatus.BUSY, second.status());
        assertNull(second.session());
    }

    @Test
    void busyReportedBeforeLocked() throws Exception {
        SessionManager m = manager();
        for (int i = 0; i < 3; i++) {
            assertEquals(AuthStatus.WRONG_CODE, m.tryReserve(ip(2), 1, wrong(m.currentCode())).status());
        }
        assertEquals(AuthStatus.LOCKED, m.tryReserve(ip(2), 1, m.currentCode()).status());
        assertEquals(AuthStatus.OK, m.tryReserve(ip(1), 1, m.currentCode()).status());
        assertEquals(AuthStatus.BUSY, m.tryReserve(ip(2), 1, m.currentCode()).status(), "BUSY wins over LOCKED");
    }

    @Test
    void wrongCodeDoesNotChangeCode() throws Exception {
        SessionManager m = manager();
        String code = m.currentCode();
        assertEquals(AuthStatus.WRONG_CODE, m.tryReserve(ip(1), 1, wrong(code)).status());
        assertEquals(code, m.currentCode());
        assertTrue(m.current().isEmpty());
    }

    @Test
    void threeFailuresWithinWindowLock() throws Exception {
        SessionManager m = manager();
        for (long t : new long[] {0, 10, 20}) {
            clock.set(s(t));
            assertEquals(AuthStatus.WRONG_CODE, m.tryReserve(ip(1), 1, wrong(m.currentCode())).status());
        }
        clock.set(s(21));
        assertEquals(AuthStatus.LOCKED, m.tryReserve(ip(1), 1, m.currentCode()).status());
        clock.set(s(50));
        assertEquals(AuthStatus.OK, m.tryReserve(ip(1), 1, m.currentCode()).status());
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() throws Exception {
        SessionManager m = manager();
        for (long t : new long[] {0, 10, 41}) {
            clock.set(s(t));
            assertEquals(AuthStatus.WRONG_CODE, m.tryReserve(ip(1), 1, wrong(m.currentCode())).status());
        }
        clock.set(s(42));
        assertEquals(AuthStatus.OK, m.tryReserve(ip(1), 1, m.currentCode()).status(), "count restarted at 41 s");
    }

    @Test
    void lockIsPerIp() throws Exception {
        SessionManager m = manager();
        for (int i = 0; i < 3; i++) m.tryReserve(ip(1), 1, wrong(m.currentCode()));
        assertEquals(AuthStatus.LOCKED, m.tryReserve(ip(1), 1, m.currentCode()).status());
        assertEquals(AuthStatus.OK, m.tryReserve(ip(2), 1, m.currentCode()).status());
    }

    @Test
    void badVersionReported() throws Exception {
        SessionManager m = manager();
        assertEquals(AuthStatus.BAD_VERSION, m.tryReserve(ip(1), 2, m.currentCode()).status());
        for (int i = 0; i < 5; i++) m.tryReserve(ip(1), 2, wrong(m.currentCode()));
        assertEquals(AuthStatus.OK, m.tryReserve(ip(1), 1, m.currentCode()).status(),
                "a bad version never counts as a failed code");
        assertEquals(AuthStatus.BAD_VERSION, m.tryReserve(ip(2), 0, m.currentCode()).status(),
                "version is checked before BUSY");
    }

    @Test
    void concurrentReserveOnlyOneWins() throws Exception {
        SessionManager m = manager();
        String code = m.currentCode();
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<AuthStatus>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                InetAddress peer = ip(i + 1);
                results.add(pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return m.tryReserve(peer, 1, code).status();
                }));
            }
            int ok = 0;
            int busy = 0;
            for (Future<AuthStatus> f : results) {
                AuthStatus st = f.get(WAIT_S, TimeUnit.SECONDS);
                if (st == AuthStatus.OK) ok++;
                else if (st == AuthStatus.BUSY) busy++;
            }
            assertEquals(1, ok);
            assertEquals(n - 1, busy);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleSocketsClosedCallbackDoesNotTouchNewSession() throws Exception {
        SessionManager m = manager();
        String firstCode = m.currentCode();
        ServerSession first = m.tryReserve(ip(1), 1, firstCode).session();
        m.onSessionSocketsClosed(first);
        assertTrue(m.current().isEmpty());
        String secondCode = m.currentCode();
        assertNotEquals(firstCode, secondCode, "a closed session always yields a new code");

        ServerSession second = m.tryReserve(ip(2), 1, secondCode).session();
        assertEquals(2, second.generation());
        m.onSessionSocketsClosed(first); // stale callback from the old generation
        assertSame(second, m.current().orElseThrow());
        assertEquals(secondCode, m.currentCode());
    }

    @Test
    void closingSessionStillCountsAsBusyUntilSocketsClosed() throws Exception {
        SessionManager m = manager();
        ServerSession s1 = m.tryReserve(ip(1), 1, m.currentCode()).session();
        s1.lifecycle().atomically(tx -> tx.advance(ServerPhase.RESERVED, ServerPhase.CLOSING));
        assertEquals(AuthStatus.BUSY, m.tryReserve(ip(2), 1, m.currentCode()).status());
    }

    @Test
    void noNewCodeWhileShuttingDown() throws Exception {
        SessionManager m = manager();
        String code = m.currentCode();
        ServerSession s1 = m.tryReserve(ip(1), 1, code).session();
        assertFalse(m.shuttingDown());
        m.setShuttingDown();
        assertTrue(m.shuttingDown());
        m.onSessionSocketsClosed(s1);
        assertTrue(m.current().isEmpty());
        assertEquals(code, m.currentCode());
        assertFalse(m.regenerateCode());
        assertEquals(AuthStatus.BUSY, m.tryReserve(ip(2), 1, code).status());
    }

    @Test
    void regenerateOnlyWithoutSession() throws Exception {
        SessionManager m = manager();
        String before = m.currentCode();
        assertTrue(m.regenerateCode());
        assertNotEquals(before, m.currentCode());
        m.tryReserve(ip(1), 1, m.currentCode());
        String during = m.currentCode();
        assertFalse(m.regenerateCode());
        assertEquals(during, m.currentCode());
    }

    @Test
    void regeneratedCodeDiffersFromPrevious() {
        int[] digits = {123456, 123456, 123456, 654321};
        AtomicInteger next = new AtomicInteger();
        SecureRandom stub = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return digits[Math.min(next.getAndIncrement(), digits.length - 1)];
            }
        };
        SessionManager m = manager(stub, this::newSession);
        assertEquals("123456", m.currentCode());
        assertTrue(m.regenerateCode());
        assertEquals("654321", m.currentCode(), "same digits are skipped until the code differs");
    }

    @Test
    void tokensAndGenerationsDifferBetweenSessions() throws Exception {
        SessionManager m = manager();
        ServerSession a = m.tryReserve(ip(1), 1, m.currentCode()).session();
        m.onSessionSocketsClosed(a);
        ServerSession b = m.tryReserve(ip(1), 1, m.currentCode()).session();
        assertEquals(a.generation() + 1, b.generation());
        assertFalse(Arrays.equals(a.token(), b.token()));
    }

    @Test
    void factoryCalledWithoutStartingAnything() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ServerSession> created = new ArrayList<>();
        com.ltm.rd.common.Ticker ticker = new com.ltm.rd.common.Ticker(Duration.ofSeconds(60), System::nanoTime);
        Thread caller = Thread.currentThread();
        SessionManager.SessionFactory spy = (generation, token, peer, reservedAt) -> {
            calls.incrementAndGet();
            assertSame(caller, Thread.currentThread(), "constructs on the caller, starts no thread");
            ServerSession session = newSession(generation, token, peer, reservedAt);
            assertEquals(0, ticker.registrations(), "the factory must not register a ticker");
            created.add(session);
            return session;
        };
        clock.set(s(7));
        SessionManager m = manager(new SecureRandom(), spy);
        ServerSession session = m.tryReserve(ip(1), 1, m.currentCode()).session();
        assertEquals(1, calls.get());
        assertEquals(0, ticker.registrations(), "registering the ticker is the caller's job, after tryReserve");
        ticker.close();
        assertSame(created.get(0), session);
        assertEquals(s(7), session.reservedAtNanos());
        assertEquals(ServerPhase.RESERVED, session.lifecycle().phase());
        assertEquals(0, session.lifecycle().liveWorkers(), "nothing started by the factory");
        assertFalse(session.lifecycle().isClosed());
    }

    @Test
    void factoryFailureLeavesNoSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SessionManager m = manager(new SecureRandom(), (g, t, p, r) -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("boom");
            return newSession(g, t, p, r);
        });
        assertThrows(IllegalStateException.class, () -> m.tryReserve(ip(1), 1, m.currentCode()));
        assertTrue(m.current().isEmpty());
        assertEquals(AuthStatus.OK, m.tryReserve(ip(1), 1, m.currentCode()).status());
    }
}
