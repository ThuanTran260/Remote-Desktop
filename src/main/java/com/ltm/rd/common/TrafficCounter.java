package com.ltm.rd.common;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe byte totals fed by counting stream wrappers; each byte is counted exactly once. */
public final class TrafficCounter {
    private final AtomicLong in = new AtomicLong();
    private final AtomicLong out = new AtomicLong();

    public long bytesIn() {
        return in.get();
    }

    public long bytesOut() {
        return out.get();
    }

    public InputStream countingInput(InputStream delegate) {
        return new CountingInput(delegate);
    }

    public OutputStream countingOutput(OutputStream delegate) {
        return new CountingOutput(delegate);
    }

    private final class CountingInput extends FilterInputStream {
        CountingInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) TrafficCounter.this.in.incrementAndGet();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) TrafficCounter.this.in.addAndGet(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) TrafficCounter.this.in.addAndGet(skipped);
            return skipped;
        }
    }

    private final class CountingOutput extends FilterOutputStream {
        CountingOutput(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            TrafficCounter.this.out.incrementAndGet();
        }

        // FilterOutputStream would loop over write(int); delegate in bulk and count once.
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            TrafficCounter.this.out.addAndGet(len);
        }
    }
}
