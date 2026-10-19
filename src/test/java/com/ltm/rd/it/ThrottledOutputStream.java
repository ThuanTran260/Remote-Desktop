package com.ltm.rd.it;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Test decorator that limits write throughput. Unlimited by default, so handshakes run at
 * normal speed; {@link #setRate} can change the limit at any time from another thread, even in
 * the middle of a write. When limited, data goes out in chunks of {@code max(1, rate / 100)}
 * bytes and the waits are sliced to at most 10 ms, so a new rate, {@link #close()} or an
 * interrupt takes effect within one slice (close and interrupt end the write with IOException).
 */
public final class ThrottledOutputStream extends OutputStream {
    private static final long SLICE_NANOS = 10_000_000L;

    private final OutputStream delegate;
    private final AtomicLong bytesWritten = new AtomicLong();
    private volatile long rate = Long.MAX_VALUE;
    private volatile boolean closed;

    public ThrottledOutputStream(OutputStream delegate) {
        this.delegate = delegate;
    }

    /** Bytes per second; {@code Long.MAX_VALUE} means unlimited. */
    public void setRate(long bytesPerSecond) {
        if (bytesPerSecond < 1) throw new IllegalArgumentException("rate must be positive");
        rate = bytesPerSecond;
    }

    public long bytesWritten() {
        return bytesWritten.get();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int pos = off;
        int end = off + len;
        while (pos < end) {
            checkOpen();
            long r = rate;
            if (r == Long.MAX_VALUE) {
                delegate.write(b, pos, end - pos);
                bytesWritten.addAndGet(end - pos);
                return;
            }
            int chunk = (int) Math.min(end - pos, Math.max(1, r / 100));
            delegate.write(b, pos, chunk);
            bytesWritten.addAndGet(chunk);
            pos += chunk;
            if (pos < end) waitFor(chunk, r);
        }
    }

    /** Waits the time {@code chunk} bytes take at {@code startRate}, leaving early if the rate changes. */
    private void waitFor(int chunk, long startRate) throws IOException {
        long remaining = (long) (chunk * 1_000_000_000.0 / startRate);
        while (remaining > 0) {
            checkOpen();
            if (rate != startRate) return;
            long slice = Math.min(SLICE_NANOS, remaining);
            long before = System.nanoTime();
            LockSupport.parkNanos(slice);
            remaining -= Math.max(1, System.nanoTime() - before);
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("throttled stream closed");
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("throttled write interrupted"); // flag stays set for the caller
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true; // a blocked writer notices within one slice
        delegate.close();
    }
}
