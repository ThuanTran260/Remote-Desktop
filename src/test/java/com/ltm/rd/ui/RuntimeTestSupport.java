package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Bounded waits and config builders shared by the runtime tests; nothing here sleeps to decide a result. */
final class RuntimeTestSupport {
    static final int WAIT_S = 10;
    static final InetAddress LO = InetAddress.getLoopbackAddress();

    private RuntimeTestSupport() {}

    static AppConfig serverConfig(LongSupplier clock, int cmdPort, int imgPort) {
        return new AppConfig(LO, cmdPort, imgPort, null, TestTimeouts.relaxed(), clock, Duration.ofSeconds(20));
    }

    static AppConfig clientConfig(int cmdPort, int imgPort, LongSupplier clock) {
        return new AppConfig(LO, cmdPort, imgPort, "127.0.0.1", TestTimeouts.relaxed(), clock,
                Duration.ofSeconds(20));
    }

    static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(WAIT_S, TimeUnit.SECONDS);
    }

    static void awaitTrue(BooleanSupplier condition) {
        awaitTrue("condition", condition);
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    static Snapshot await(Controller controller, Predicate<Snapshot> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        Snapshot s = controller.snapshot();
        while (!condition.test(s)) {
            assertTrue(System.nanoTime() < deadline, "timed out; last snapshot " + s + ", message=" + s.message());
            LockSupport.parkNanos(1_000_000);
            s = controller.snapshot();
        }
        return s;
    }

    /** The live threads whose name starts with {@code rd-}. */
    static Set<Thread> liveRdThreads() {
        Set<Thread> result = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("rd-")) result.add(t);
        }
        return result;
    }
}
