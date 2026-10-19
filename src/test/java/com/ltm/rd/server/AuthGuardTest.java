package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthGuardTest {
    private static final Duration WINDOW = Duration.ofSeconds(30);

    private static InetAddress ip(int n) throws Exception {
        return InetAddress.getByAddress(new byte[] {10, 0, (byte) (n >> 8), (byte) n});
    }

    private static AuthGuard guard() {
        return new AuthGuard(1024, 3, WINDOW);
    }

    @Test
    void unknownIpIsNotLocked() throws Exception {
        assertFalse(guard().isLocked(ip(1), 0));
    }

    @Test
    void thirdFailureWithinWindowLocksUntilWindowPasses() throws Exception {
        AuthGuard g = guard();
        g.recordFailure(ip(1), s(0));
        g.recordFailure(ip(1), s(10));
        assertFalse(g.isLocked(ip(1), s(10)));
        g.recordFailure(ip(1), s(20));
        assertTrue(g.isLocked(ip(1), s(20)));
        assertTrue(g.isLocked(ip(1), s(50) - 1), "locked until lockedUntil = 20 s + window");
        assertFalse(g.isLocked(ip(1), s(50)));
    }

    @Test
    void unlockedEntryExpiresAtLastFailurePlusWindow() throws Exception {
        AuthGuard g = guard();
        g.recordFailure(ip(1), s(0));
        g.recordFailure(ip(1), s(10));
        g.recordFailure(ip(1), s(40) - 1); // still inside the window of the last failure: third failure
        assertTrue(g.isLocked(ip(1), s(40)));

        AuthGuard h = guard();
        h.recordFailure(ip(1), s(0));
        h.recordFailure(ip(1), s(10));
        h.recordFailure(ip(1), s(40)); // 10 s + 30 s: the entry expired, count restarts at 1
        assertFalse(h.isLocked(ip(1), s(40)));
        h.recordFailure(ip(1), s(41));
        assertFalse(h.isLocked(ip(1), s(41)), "only two failures since the restart");
    }

    @Test
    void successClearsEntry() throws Exception {
        AuthGuard g = guard();
        g.recordFailure(ip(1), 0);
        g.recordFailure(ip(1), 1);
        g.recordSuccess(ip(1));
        g.recordFailure(ip(1), 2);
        assertFalse(g.isLocked(ip(1), 2));
        assertEquals(1, g.size());
    }

    @Test
    void lockIsPerIp() throws Exception {
        AuthGuard g = guard();
        for (int i = 0; i < 3; i++) g.recordFailure(ip(1), s(i));
        assertTrue(g.isLocked(ip(1), s(3)));
        assertFalse(g.isLocked(ip(2), s(3)));
    }

    @Test
    void guardTableBoundedAt1024() throws Exception {
        AuthGuard g = guard();
        for (int i = 0; i < 1024; i++) g.recordFailure(ip(i), 0);
        assertEquals(1024, g.size());
        assertTrue(g.isLocked(ip(5000), s(1)), "full table: unknown IPs are treated as locked");
        g.recordFailure(ip(5000), s(1));
        assertEquals(1024, g.size(), "never grows beyond the bound");
        assertFalse(g.isLocked(ip(5), s(1)), "known unlocked IP stays unlocked");

        // once entries expire they are evicted and new IPs are accepted again
        assertFalse(g.isLocked(ip(5000), s(30)));
        g.recordFailure(ip(5000), s(30));
        assertTrue(g.size() <= 1024);
    }

    @Test
    void lockedEntriesAreNotEvictedBeforeTheirExpiry() throws Exception {
        AuthGuard g = new AuthGuard(2, 1, WINDOW);
        g.recordFailure(ip(1), 0); // locks at once until 30 s
        g.recordFailure(ip(2), 0);
        assertTrue(g.isLocked(ip(3), s(29)), "table full of live entries");
        assertTrue(g.isLocked(ip(1), s(29)));
        assertFalse(g.isLocked(ip(3), s(30)));
    }
}
