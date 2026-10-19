package com.ltm.rd.common;

import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
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
    private Thread thread; // guarded by lock; set when the worker begins running
    private boolean started; // guarded by lock
    private boolean closing; // guarded by lock
    private boolean stopped; // guarded by lock
    private Throwable failure; // guarded by lock
    private Packet finalPacket; // guarded by lock
    private CompletableFuture<Void> done; // guarded by lock

    private final WriteTracker tracker = new WriteTracker();

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
        Thread t = new Thread(r, "rd-cmd-writer-" + nextThreadName());
        t.setDaemon(true);
        return t;
    }

    private static String nextThreadName() {
        return Integer.toString(THREAD_SEQ.incrementAndGet());
    }

    /** Starts the writer thread; no effect if already started, closing or stopped. */
    public void start() {
        Thread t;
        synchronized (lock) {
            if (started || closing || stopped) return;
            t = threadFactory.newThread(this::run);
            thread = t;
            started = true;
        }
        t.start();
    }

    /**
     * Starts the writer through {@code starter} (e.g. a session lifecycle). Returns false if it
     * was already started, closing or stopped, or if the starter refused; in the refused case the
     * writer is stopped, so {@link #offer} returns CLOSED and a pending close future fails.
     */
    public boolean start(WorkerStarter starter) {
        Objects.requireNonNull(starter);
        synchronized (lock) {
            if (started || closing || stopped) return false;
            started = true;
        }
        boolean accepted;
        try {
            accepted = starter.start("rd-cmd-writer-" + nextThreadName(), this::run);
        } catch (Throwable e) {
            stopInternal(false);
            throw e;
        }
        if (!accepted) stopInternal(false);
        return accepted;
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
            if (!started) {
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
        stopInternal(true);
    }

    /** {@code interrupt} is false when called on the writer thread itself. */
    private void stopInternal(boolean interrupt) {
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
        if (interrupt && t != null) t.interrupt();
        if (pending != null) pending.completeExceptionally(new IllegalStateException("writer stopped"));
    }

    /** Atomic write state; a watchdog expires it, after which this writer sends nothing more. */
    public WriteTracker tracker() {
        return tracker;
    }

    /** Start of the write in progress (from the injected clock), or 0 when idle. */
    public long writeStartedAtNanos() {
        return tracker.writeStartedAtNanos();
    }

    /** Incremented at the start of every write; lets a watchdog tell one write from the next. */
    public long writeOperationId() {
        return tracker.writeOperationId();
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
                if (thread == null) thread = Thread.currentThread();
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
        long op = tracker.begin(nanoClock.getAsLong());
        if (op == WriteTracker.EXPIRED_OP) { // a watchdog declared the previous write stuck
            stopInternal(false);
            return false;
        }
        boolean ok = false;
        try {
            PacketIO.write(out, packet);
            out.flush();
            ok = true;
        } catch (Throwable e) { // incl. RuntimeException: the writer thread must never die silently
            fail(e);
        } finally {
            if (!tracker.end(op) && ok) { // expired while writing: send nothing more
                stopInternal(false);
                ok = false;
            }
        }
        return ok;
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
