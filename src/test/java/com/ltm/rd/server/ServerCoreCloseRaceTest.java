package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * A close() that lands while start() is still creating its executors, ticker, pool and acceptors.
 * The seam {@code beforeResources} holds start() in that window, so every run hits the race.
 */
@Timeout(60)
class ServerCoreCloseRaceTest {
    private final com.ltm.rd.server.ServerTestAccess.HostResources hostResources = new com.ltm.rd.server.ServerTestAccess.HostResources();

    @org.junit.jupiter.api.AfterEach void closeHostResources() { hostResources.close(); }

    private static final InetAddress LO = InetAddress.getLoopbackAddress();
    private static final long WAIT_S = 10;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, LO)) {
            return s.getLocalPort();
        }
    }

    private static Set<Thread> liveRdThreads() {
        Set<Thread> result = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("rd-")) result.add(t);
        }
        return result;
    }

    private static Set<String> newRdThreadNames(Set<Thread> before) {
        Set<String> names = new TreeSet<>();
        for (Thread t : liveRdThreads()) {
            if (!before.contains(t)) names.add(t.getName());
        }
        return names;
    }

    /**
     * Starts a core whose start() is overtaken by close() right after the sockets are published, then
     * checks that nothing start() created is left behind.
     *
     * @param closeOnOtherThread false: close() runs inside the window on start()'s own thread; true: it runs
     *                           on another thread while start() waits in the window for it to finish
     */
    private void startOvertakenByClose(boolean closeOnOtherThread) throws Exception {
        Set<Thread> before = liveRdThreads();
        int cmd = freePort();
        int img = freePort();
        ServerCore[] holder = new ServerCore[1];
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        // close() completes after start() checked "closed" but before any executor exists, so it finds the
        // scheduler, cleanup, ticker, pool and acceptors still null
        Runnable window = closeOnOtherThread
                ? () -> {
                    inWindow.countDown();
                    await(closeDone);
                }
                : () -> holder[0].close();
        ServerCore.Seams seams = new ServerCore.Seams(null, null, null, false, null, window);
        ServerCore core = hostResources.core(new ServerConfig(LO, cmd, img, Timeouts.DEFAULTS, System::nanoTime,
                new SecureRandom()), seams);
        holder[0] = core;
        Thread closer = new Thread(() -> {
            await(inWindow);
            core.close();
            closeDone.countDown();
        }, "test-closer");
        if (closeOnOtherThread) closer.start();

        IllegalStateException thrown = null;
        try {
            core.start();
        } catch (IllegalStateException e) {
            thrown = e;
        }
        if (closeOnOtherThread) closer.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertEquals(0, core.tickerRegistrations(), "late resource cleanup releases the pending sweep");

        // every thread that start() created (rd-ticker, rd-accept-*, and rd-close-timer / rd-cleanup-* /
        // rd-handshake-* if they were ever started) is gone
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        Set<String> leaked = newRdThreadNames(before);
        while (!leaked.isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000);
            leaked = newRdThreadNames(before);
        }
        assertEquals(Set.of(), leaked, "threads left behind by a start() that raced close()");
        assertNotNull(thrown, "a start() overtaken by close() fails, like the earlier bind/publish window");
        // both ports are free again
        try (ServerSocket a = new ServerSocket(cmd, 1, LO); ServerSocket b = new ServerSocket(img, 1, LO)) {
            assertTrue(a.isBound() && b.isBound());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS), "latch not released in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @RepeatedTest(10)
    void closeOnTheStartThreadBetweenPublishAndResourceSetupLeaksNothing() throws Exception {
        startOvertakenByClose(false);
    }

    @RepeatedTest(10)
    void closeOnAnotherThreadBetweenPublishAndResourceSetupLeaksNothing() throws Exception {
        startOvertakenByClose(true);
    }
}
