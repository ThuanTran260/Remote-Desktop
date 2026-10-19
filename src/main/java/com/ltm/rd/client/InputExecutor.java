package com.ltm.rd.client;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs input commands on one worker thread with session generations.
 *
 * <p>One short metadata lock guards the phase, generations, deque, END slot and the claim of the
 * next command. The {@link InputDevice}, futures and callbacks are never touched under that lock.
 * Held keys/buttons are only accessed from the worker thread.
 */
public final class InputExecutor {
    public enum State { IDLE, OPEN, ENDING, FAULTED, STOPPED }

    private static final System.Logger LOG = System.getLogger(InputExecutor.class.getName());
    private static final long WARN_INTERVAL_NANOS = 1_000_000_000L;

    private record EndRecord(long gen, CompletableFuture<Void> future) {}

    private final InputDevice device;
    private final Supplier<Rectangle> bounds;
    private final int capacity;
    private final Consumer<Throwable> onFault;

    private final Object lock = new Object();
    // Guarded by lock.
    private State state = State.IDLE;
    private long currentGen;
    private long maxGen = Long.MIN_VALUE;
    private final ArrayDeque<InputCommand> deque = new ArrayDeque<>();
    private EndRecord endRecord; // only the latest end is kept
    private boolean endPending;
    private boolean stopRequested;
    private Throwable lastFault;

    // Worker thread only.
    private final Set<Integer> heldKeys = new LinkedHashSet<>();
    private final Set<Integer> heldButtons = new LinkedHashSet<>();
    private long lastWarnNanos;
    private boolean warned;

    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    public InputExecutor(InputDevice device, Supplier<Rectangle> bounds, int capacity,
                         ThreadFactory threadFactory, Consumer<Throwable> onFault) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.device = Objects.requireNonNull(device);
        this.bounds = Objects.requireNonNull(bounds);
        this.capacity = capacity;
        this.onFault = Objects.requireNonNull(onFault);
        Objects.requireNonNull(threadFactory).newThread(this::run).start();
    }

    /** Daemon factory producing the {@code rd-input} worker thread. */
    public static ThreadFactory defaultThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "rd-input");
            t.setDaemon(true);
            return t;
        };
    }

    // ---- session interface (caller threads) ----

    public boolean beginSession(long gen) {
        synchronized (lock) {
            if (state != State.IDLE || stopRequested || gen <= maxGen) {
                return false;
            }
            maxGen = gen;
            currentGen = gen;
            state = State.OPEN;
            deque.clear();
            return true;
        }
    }

    public SubmitResult submit(long gen, InputCommand cmd) {
        Objects.requireNonNull(cmd);
        synchronized (lock) {
            if (state != State.OPEN || currentGen != gen) {
                return SubmitResult.STALE;
            }
            if (deque.size() >= capacity) {
                return SubmitResult.OVERFLOW;
            }
            deque.addLast(cmd);
            lock.notifyAll();
            return SubmitResult.ACCEPTED;
        }
    }

    public CompletableFuture<Void> endSession(long gen) {
        synchronized (lock) {
            return endLocked(gen);
        }
    }

    /** Refuses new begins, ends the current generation if any, then stops the worker. */
    public CompletableFuture<Void> shutdownAsync() {
        synchronized (lock) {
            stopRequested = true;
            if (state == State.OPEN) {
                endLocked(currentGen);
            }
            lock.notifyAll();
        }
        return stopped;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public Throwable lastFault() {
        synchronized (lock) {
            return lastFault;
        }
    }

    // Caller holds lock.
    private CompletableFuture<Void> endLocked(long gen) {
        if (endRecord != null && endRecord.gen() == gen) {
            return endRecord.future();
        }
        // A session faulted mid-way still gets a release attempt, but stays FAULTED.
        boolean live = (state == State.OPEN || state == State.FAULTED) && currentGen == gen;
        if (!live) {
            return CompletableFuture.completedFuture(null);
        }
        if (state == State.OPEN) {
            state = State.ENDING;
        }
        deque.clear();
        endRecord = new EndRecord(gen, new CompletableFuture<>());
        endPending = true;
        lock.notifyAll();
        return endRecord.future();
    }

    // ---- worker ----

    private void run() {
        try {
            for (;;) {
                Object work = next();
                if (work == null) {
                    break;
                }
                try {
                    if (work instanceof EndRecord rec) {
                        runEnd(rec);
                    } else {
                        execute((InputCommand) work);
                    }
                } catch (Throwable t) {
                    fault(t);
                }
            }
        } finally {
            stopped.complete(null);
        }
    }

    /** Blocks until work is available; claiming a command happens atomically under the lock. */
    private Object next() {
        synchronized (lock) {
            for (;;) {
                if (endPending) {
                    endPending = false;
                    return endRecord;
                }
                if (state == State.OPEN && !deque.isEmpty()) {
                    return deque.pollFirst();
                }
                if (stopRequested && state != State.OPEN) {
                    state = State.STOPPED;
                    return null;
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // An interrupt is not a stop signal; shutdownAsync() is.
                }
            }
        }
    }

    private void runEnd(EndRecord rec) {
        Throwable failure = releaseHeld();
        Throwable cause;
        synchronized (lock) {
            if (failure == null && state == State.ENDING) {
                state = State.IDLE;
                cause = null;
            } else {
                if (failure != null) {
                    lastFault = failure;
                }
                state = State.FAULTED;
                cause = lastFault;
            }
        }
        if (failure != null) {
            notifyFault(failure);
        }
        if (cause == null) {
            rec.future().complete(null);
        } else {
            rec.future().completeExceptionally(cause);
        }
    }

    private void fault(Throwable t) {
        synchronized (lock) {
            if (state != State.STOPPED) {
                state = State.FAULTED;
            }
            lastFault = t;
            deque.clear();
        }
        notifyFault(t);
    }

    private void notifyFault(Throwable t) {
        try {
            onFault.accept(t);
        } catch (Throwable ignored) {
            // A faulty callback must not kill the worker.
        }
    }

    private void execute(InputCommand cmd) {
        switch (cmd) {
            case InputCommand.MouseMove m -> {
                Rectangle b = bounds.get();
                if (inside(b, m.x(), m.y())) {
                    device.mouseMove(b.x + m.x(), b.y + m.y());
                }
            }
            case InputCommand.MousePress p -> {
                if (heldButtons.contains(p.button())) {
                    return;
                }
                Rectangle b = bounds.get();
                if (!inside(b, p.x(), p.y())) {
                    return;
                }
                device.mouseMove(b.x + p.x(), b.y + p.y());
                heldButtons.add(p.button());
                device.mousePress(p.button());
            }
            case InputCommand.MouseRelease r -> {
                if (!heldButtons.contains(r.button())) {
                    return;
                }
                Rectangle b = bounds.get();
                if (inside(b, r.x(), r.y())) {
                    device.mouseMove(b.x + r.x(), b.y + r.y());
                }
                // The release is attempted even when the coordinates are no longer valid.
                device.mouseRelease(r.button());
                heldButtons.remove(r.button());
            }
            case InputCommand.Wheel w -> device.mouseWheel(w.notches());
            case InputCommand.KeyPress k -> {
                boolean wasHeld = !heldKeys.add(k.code());
                try {
                    device.keyPress(k.code()); // duplicates are forwarded to support key repeat
                } catch (IllegalArgumentException e) {
                    if (!wasHeld) {
                        heldKeys.remove(k.code());
                    }
                    warnUnsupportedKey(k.code(), e);
                }
            }
            case InputCommand.KeyRelease k -> {
                if (heldKeys.contains(k.code())) {
                    device.keyRelease(k.code());
                    heldKeys.remove(k.code());
                }
            }
            case InputCommand.ReleaseAll ignored -> {
                Throwable failure = releaseHeld();
                if (failure != null) {
                    fault(failure);
                }
            }
        }
    }

    /** Attempts every held key and button; returns the first failure (others suppressed). */
    private Throwable releaseHeld() {
        Throwable first = null;
        for (Integer key : new ArrayList<>(heldKeys)) {
            try {
                device.keyRelease(key);
                heldKeys.remove(key);
            } catch (Throwable t) {
                first = merge(first, t);
            }
        }
        for (Integer button : new ArrayList<>(heldButtons)) {
            try {
                device.mouseRelease(button);
                heldButtons.remove(button);
            } catch (Throwable t) {
                first = merge(first, t);
            }
        }
        return first;
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean inside(Rectangle b, int x, int y) {
        return x >= 0 && y >= 0 && x < b.width && y < b.height;
    }

    private void warnUnsupportedKey(int code, IllegalArgumentException e) {
        long now = System.nanoTime();
        if (!warned || now - lastWarnNanos >= WARN_INTERVAL_NANOS) {
            warned = true;
            lastWarnNanos = now;
            LOG.log(System.Logger.Level.WARNING, "Unsupported key code " + code + ", skipped", e);
        }
    }
}
