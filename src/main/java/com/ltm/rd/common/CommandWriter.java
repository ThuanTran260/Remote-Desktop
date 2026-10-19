package com.ltm.rd.common;

import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Single writer thread for one command channel, fed by a bounded queue.
 *
 * <p>The queue lock only guards metadata; it is never held during I/O or while calling out
 * (error handler, future completion). Every packet is flushed after writing.
 */
public final class CommandWriter {
    public static final int DEFAULT_CAPACITY = 256;

    public enum OfferResult { ACCEPTED, COALESCED, CLOSED, OVERFLOW }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final DataOutputStream out;
    private final int capacity;
    private final ThreadFactory threadFactory;
    private final Consumer<Throwable> onWriteError;
    private final LongSupplier nanoClock;

    private final Object lock = new Object();
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private Thread thread; // guarded by lock
    private boolean closing; // guarded by lock
    private boolean stopped; // guarded by lock
    private Throwable failure; // guarded by lock
    private Packet finalPacket; // guarded by lock
    private CompletableFuture<Void> done; // guarded by lock

    private volatile long writeStartedAtNanos;
    private final AtomicLong writeOperationId = new AtomicLong();

    public CommandWriter(DataOutputStream out, Consumer<Throwable> onWriteError) {
        this(out, DEFAULT_CAPACITY, CommandWriter::newDaemonThread, onWriteError, System::nanoTime);
    }

    public CommandWriter(DataOutputStream out, int capacity, ThreadFactory threadFactory,
            Consumer<Throwable> onWriteError, LongSupplier nanoClock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.out = Objects.requireNonNull(out);
        this.capacity = capacity;
        this.threadFactory = Objects.requireNonNull(threadFactory);
        this.onWriteError = Objects.requireNonNull(onWriteError);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    private static Thread newDaemonThread(Runnable r) {
        Thread t = new Thread(r, "rd-cmd-writer-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    /** Starts the writer thread; no effect if already started, closing or stopped. */
    public void start() {
        Thread t;
        synchronized (lock) {
            if (thread != null || closing || stopped) return;
            t = threadFactory.newThread(this::run);
            thread = t;
        }
        t.start();
    }

    /** Never blocks. A full queue yields OVERFLOW; the caller is responsible for closing the session. */
    public OfferResult offer(Packet packet) {
        Objects.requireNonNull(packet);
        synchronized (lock) {
            if (closing || stopped) return OfferResult.CLOSED;
            if (packet.type() == PacketType.MOUSE_MOVE && !queue.isEmpty()
                    && queue.peekLast().type() == PacketType.MOUSE_MOVE) {
                queue.pollLast();
                queue.addLast(packet);
                return OfferResult.COALESCED;
            }
            if (queue.size() >= capacity) return OfferResult.OVERFLOW;
            queue.addLast(packet);
            lock.notifyAll();
            return OfferResult.ACCEPTED;
        }
    }

    /**
     * Non-blocking graceful close: stops accepting offers, discards queued packets, lets the
     * in-flight packet finish, then writes and flushes {@code finalOrNull}. The returned future
     * completes after that flush (or exceptionally on write error / stop). Repeat calls return
     * the same future and ignore their argument. If the writer was never started nothing can be
     * written, so the future completes immediately.
     */
    public CompletableFuture<Void> closeGracefully(Packet finalOrNull) {
        synchronized (lock) {
            if (done != null) return done;
            if (failure != null || stopped) {
                closing = true;
                done = CompletableFuture.failedFuture(
                        failure != null ? failure : new IllegalStateException("writer stopped"));
                return done;
            }
            closing = true;
            queue.clear();
            if (thread == null) {
                done = CompletableFuture.completedFuture(null);
                return done;
            }
            finalPacket = finalOrNull;
            done = new CompletableFuture<>();
            lock.notifyAll();
            return done;
        }
    }

    /** Stops the writer thread. Idempotent; a still-pending close future fails. */
    public void stop() {
        Thread t;
        CompletableFuture<Void> pending;
        synchronized (lock) {
            stopped = true;
            closing = true;
            queue.clear();
            finalPacket = null;
            t = thread;
            pending = done;
            lock.notifyAll();
        }
        if (t != null) t.interrupt();
        if (pending != null) pending.completeExceptionally(new IllegalStateException("writer stopped"));
    }

    /** Start of the write in progress (from the injected clock), or 0 when idle. */
    public long writeStartedAtNanos() {
        return writeStartedAtNanos;
    }

    /** Incremented at the start of every write; lets a watchdog tell one write from the next. */
    public long writeOperationId() {
        return writeOperationId.get();
    }

    /** Copy of the not-yet-started packets, for tests. */
    List<Packet> queuedSnapshot() {
        synchronized (lock) {
            return new ArrayList<>(queue);
        }
    }

    private void run() {
        while (true) {
            Packet next;
            CompletableFuture<Void> finish = null;
            synchronized (lock) {
                try {
                    while (!stopped && !closing && queue.isEmpty()) lock.wait();
                } catch (InterruptedException e) {
                    return;
                }
                if (stopped || failure != null) return;
                if (closing) {
                    next = finalPacket;
                    finalPacket = null;
                    if (next == null) finish = done;
                } else {
                    next = queue.pollFirst(); // from here on the packet is in flight
                }
            }
            if (next == null) {
                if (finish != null) finish.complete(null);
                return;
            }
            if (!write(next)) return;
        }
    }

    /** Returns false if the write failed and the loop must end. */
    private boolean write(Packet packet) {
        writeOperationId.incrementAndGet();
        long now = nanoClock.getAsLong();
        writeStartedAtNanos = now == 0 ? 1 : now; // 0 is reserved for "idle"
        try {
            PacketIO.write(out, packet);
            out.flush();
            return true;
        } catch (Throwable e) { // incl. RuntimeException: the writer thread must never die silently
            fail(e);
            return false;
        } finally {
            writeStartedAtNanos = 0;
        }
    }

    private void fail(Throwable cause) {
        boolean report;
        CompletableFuture<Void> pending;
        synchronized (lock) {
            report = !stopped && failure == null;
            if (failure == null) failure = cause;
            closing = true;
            queue.clear();
            finalPacket = null;
            pending = done;
        }
        if (report) {
            try {
                onWriteError.accept(cause);
            } catch (Throwable ignored) {
                // a failing handler must not keep the close future pending
            }
        }
        if (pending != null) pending.completeExceptionally(cause);
    }
}
