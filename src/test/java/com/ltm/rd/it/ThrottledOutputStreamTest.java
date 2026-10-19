package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ThrottledOutputStreamTest {
    private static final int WAIT_S = 10;

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    @Test
    void unlimitedByDefault() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink);
        long start = System.nanoTime();
        out.write(new byte[1_000_000]);
        assertEquals(1_000_000, sink.size());
        assertTrue(ms(System.nanoTime() - start) < 1000, "no throttling without setRate");
    }

    @Test
    void limitedRateSlowsTheWrite() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink);
        out.setRate(1000); // chunks of 10 bytes, 10 ms each
        long start = System.nanoTime();
        out.write(new byte[200]);
        assertEquals(200, sink.size());
        assertTrue(ms(System.nanoTime() - start) >= 150, "200 bytes at 1000 B/s need about 200 ms");
    }

    @Test
    void raisingTheRateMidWriteReleasesTheWriter() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink);
        out.setRate(10); // 1 byte per 100 ms: 1000 bytes would take 100 s
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            try {
                started.countDown();
                out.write(new byte[1000]);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS));
        out.setRate(Long.MAX_VALUE);
        writer.get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(1000, sink.size());
    }

    @Test
    void closeWakesBlockedWriterQuickly() throws Exception {
        ThrottledOutputStream out = new ThrottledOutputStream(new ByteArrayOutputStream());
        out.setRate(1); // 1 byte per second
        CountDownLatch writing = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Long> failedAt = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                writing.countDown();
                out.write(new byte[100]);
            } catch (Throwable t) {
                failure.set(t);
                failedAt.set(System.nanoTime());
            }
        }, "test-throttled-writer");
        writer.setDaemon(true);
        writer.start();
        assertTrue(writing.await(WAIT_S, TimeUnit.SECONDS));
        long closedAt = System.nanoTime();
        out.close();
        writer.join(WAIT_S * 1000L);
        assertFalse(writer.isAlive());
        assertInstanceOf(IOException.class, failure.get());
        assertTrue(ms(failedAt.get() - closedAt) < 100, "the writer must notice close within 100 ms");
    }

    @Test
    void interruptWakesBlockedWriterQuickly() throws Exception {
        ThrottledOutputStream out = new ThrottledOutputStream(new ByteArrayOutputStream());
        out.setRate(1);
        CountDownLatch writing = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                writing.countDown();
                out.write(new byte[100]);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-throttled-writer");
        writer.setDaemon(true);
        writer.start();
        assertTrue(writing.await(WAIT_S, TimeUnit.SECONDS));
        writer.interrupt();
        writer.join(WAIT_S * 1000L);
        assertFalse(writer.isAlive());
        assertInstanceOf(IOException.class, failure.get());
    }
}
