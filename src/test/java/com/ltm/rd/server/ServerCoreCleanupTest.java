package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ServerCoreCleanupTest {
    private static final InetAddress LO = InetAddress.getLoopbackAddress();
    private static final long WAIT_S = 10;

    private static ServerCore core() {
        return new ServerCore(new ServerConfig(LO, 0, 0, Timeouts.DEFAULTS, System::nanoTime,
                new SecureRandom()));
    }

    @Test
    void defaultConfigurationReleasesPendingRegistrationAndPorts() throws Exception {
        Set<Thread> before = liveRdThreads();
        ServerCore core = core();
        try {
            core.start();
            int cmd = core.cmdPort();
            int img = core.imgPort();
            assertEquals(1, core.tickerRegistrations(), "the default pending sweep is registered");

            core.close();
            assertEquals(0, core.tickerRegistrations(), "close releases the pending sweep");
            core.close();
            assertEquals(0, core.tickerRegistrations(), "repeated close keeps the registration released");
            try (ServerSocket a = new ServerSocket(cmd, 1, LO); ServerSocket b = new ServerSocket(img, 1, LO)) {
                assertTrue(a.isBound() && b.isBound(), "both listening ports are reusable");
            }
            awaitNoNewRdThreads(before);
        } finally {
            core.close();
        }
    }

    @Test
    void closeBeforeStartDoesNotCreateResources() {
        Set<Thread> before = liveRdThreads();
        ServerCore core = core();
        try {
            core.close();
            assertEquals(0, core.tickerRegistrations());
            core.close();
            assertEquals(0, core.tickerRegistrations());
            assertThrows(IllegalStateException.class, core::start);
            assertEquals(0, core.tickerRegistrations(), "a rejected start creates no pending sweep");
            awaitNoNewRdThreads(before);
        } finally {
            core.close();
        }
    }

    private static Set<Thread> liveRdThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("rd-"))
                .collect(Collectors.toSet());
    }

    private static Set<String> newRdThreadNames(Set<Thread> before) {
        Set<String> names = new TreeSet<>();
        for (Thread t : liveRdThreads()) {
            if (!before.contains(t)) names.add(t.getName());
        }
        return names;
    }

    private static void awaitNoNewRdThreads(Set<Thread> before) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        Set<String> leaked = newRdThreadNames(before);
        while (!leaked.isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000);
            leaked = newRdThreadNames(before);
        }
        assertEquals(Set.of(), leaked, "all new rd-* threads have stopped");
    }
}
