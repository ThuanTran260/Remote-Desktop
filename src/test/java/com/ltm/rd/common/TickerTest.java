package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TickerTest {
    @Test
    void tickerKeepsRunningAfterTickableThrows() throws Exception {
        try (Ticker ticker = new Ticker(Duration.ofMillis(10), System::nanoTime)) {
            AtomicInteger thrown = new AtomicInteger();
            CountDownLatch counted = new CountDownLatch(3);
            ticker.register(now -> {
                thrown.incrementAndGet();
                throw new IllegalStateException("boom");
            });
            ticker.register(now -> counted.countDown());
            assertTrue(counted.await(2, TimeUnit.SECONDS), "healthy tickable must keep running");
            assertTrue(thrown.get() >= 3, "throwing tickable is still called on later ticks");
        }
    }

    @Test
    void registrationCloseUnregisters() {
        try (Ticker ticker = new Ticker(Duration.ofSeconds(60), System::nanoTime)) {
            Tickable t = now -> {};
            Ticker.Registration a = ticker.register(t);
            Ticker.Registration b = ticker.register(t);
            assertEquals(2, ticker.registrations());
            a.close();
            assertEquals(1, ticker.registrations());
            b.close();
            assertEquals(0, ticker.registrations());
        }
    }

    @Test
    void passesClockTimeAndRunsOnNamedDaemonThread() throws Exception {
        FakeClock clock = new FakeClock();
        clock.set(42);
        try (Ticker ticker = new Ticker(Duration.ofMillis(10), clock)) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            CountDownLatch seen = new CountDownLatch(1);
            ticker.register(now -> {
                if (now == 42) {
                    thread.set(Thread.currentThread());
                    seen.countDown();
                }
            });
            assertTrue(seen.await(2, TimeUnit.SECONDS));
            assertEquals("rd-ticker", thread.get().getName());
            assertTrue(thread.get().isDaemon());
        }
    }
}
