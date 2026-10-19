package com.ltm.rd.common;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Owns one session's phase, registered sockets, workers and two-phase close behind a private lock.
 *
 * <p>All state changes go through {@link #atomically}: a critical section without rollback. Side
 * effects (hooks, closing, starting threads, callbacks) are deferred with {@link Tx#after} and run
 * only after the outermost {@code atomically} released the lock, in registration order.
 *
 * <p>Close procedure, after the lock is released: (1) schedule a forced raw-socket close at
 * {@code closeStartedAt + closeGrace}; (2) run close hooks; (3) call the graceful step; (4) hand
 * phase B to {@code cleanup}. Phase B waits for the step up to the absolute deadline and then, in
 * this order: closes every raw socket, completes {@link #socketsClosed()}, interrupts workers, runs
 * stop hooks (which close stream wrappers; never before the interrupt, because a throttled writer
 * may hold a wrapper).
 */
public final class SessionLifecycle<P extends Enum<P>> {
    private static final System.Logger LOG = System.getLogger(SessionLifecycle.class.getName());

    /** View of the session valid only inside the {@code atomically} callback that received it. */
    public interface Tx<P> {
        P phase();

        boolean isClosed();

        boolean advance(P from, P to);

        /** Only marks the session closed; the close procedure runs after the lock is released. */
        boolean close(CloseInfo info);

        boolean closeIf(P expected, CloseInfo info);

        /** Registers a socket; if already closed, closes it after the lock is released and throws. */
        Socket register(Socket s);

        /** False if already closed; the caller must then not start its writer. */
        boolean setGracefulStep(Function<CloseInfo, CompletableFuture<?>> step);

        void after(Runnable action);
    }

    private final P closingPhase;
    private final LongSupplier nanoClock;
    private final Duration closeGrace;
    private final ScheduledExecutorService scheduler;
    private final Executor cleanup;
    private final ThreadFactory threadFactory;

    private final ReentrantLock lock = new ReentrantLock();
    private int depth; // guarded by lock
    private List<Runnable> deferred = new ArrayList<>(); // guarded by lock
    private P phase; // guarded by lock
    private CloseInfo closeInfo; // guarded by lock; non-null once closed
    private long closeStartedAt; // guarded by lock
    private final Set<Socket> sockets = new LinkedHashSet<>(); // guarded by lock
    private final List<Runnable> closeHooks = new ArrayList<>(); // guarded by lock
    private final List<Runnable> stopHooks = new ArrayList<>(); // guarded by lock
    private boolean stopPhaseStarted; // guarded by lock
    private boolean stopHooksDone; // guarded by lock
    private Function<CloseInfo, CompletableFuture<?>> gracefulStep; // guarded by lock
    private int workerCount; // guarded by lock; STARTING + running
    private final Set<Thread> runningWorkers = new HashSet<>(); // guarded by lock

    private final AtomicBoolean finished = new AtomicBoolean(); // set once phase B work has started
    private final CompletableFuture<CloseInfo> socketsClosed =new CompletableFuture<>();
    private final CompletableFuture<CloseInfo> terminated = new CompletableFuture<>();

    public SessionLifecycle(P initial, P closing, LongSupplier nanoClock, Duration closeGrace,
            ScheduledExecutorService scheduler, Executor cleanup) {
        this(initial, closing, nanoClock, closeGrace, scheduler, cleanup, Thread::new);
    }

    /** Seam for tests in other packages: the factory lets them intercept thread creation and start. */
    public SessionLifecycle(P initial, P closing, LongSupplier nanoClock, Duration closeGrace,
            ScheduledExecutorService scheduler, Executor cleanup, ThreadFactory threadFactory) {
        this.phase = Objects.requireNonNull(initial);
        this.closingPhase = Objects.requireNonNull(closing);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.closeGrace = Objects.requireNonNull(closeGrace);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.threadFactory = Objects.requireNonNull(threadFactory);
    }

    // ---- transactions ----------------------------------------------------------------------

    /**
     * Runs {@code body} under the private lock (re-entrant). If it throws, the session is closed
     * with PROTOCOL_ERROR "internal error" (unless already closed), every registered action still
     * runs, and the exception is rethrown.
     */
    public <R> R atomically(Function<Tx<P>, R> body) {
        Objects.requireNonNull(body);
        TxImpl tx = new TxImpl();
        R result = null;
        Throwable failure = null;
        List<Runnable> actions = null;
        lock.lock();
        try {
            depth++;
            try {
                result = body.apply(tx);
            } catch (Throwable t) {
                failure = t;
                markClose(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "internal error", true));
            } finally {
                tx.valid = false;
                depth--;
                if (depth == 0 && !deferred.isEmpty()) {
                    actions = deferred;
                    deferred = new ArrayList<>();
                }
            }
        } finally {
            lock.unlock();
        }
        if (actions != null) runAll(actions);
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
        return result;
    }

    private final class TxImpl implements Tx<P> {
        volatile boolean valid = true;

        private void check() {
            if (!valid || !lock.isHeldByCurrentThread()) {
                throw new IllegalStateException("Tx used outside its atomically callback");
            }
        }

        @Override
        public P phase() {
            check();
            return phase;
        }

        @Override
        public boolean isClosed() {
            check();
            return closeInfo != null;
        }

        @Override
        public boolean advance(P from, P to) {
            check();
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (closeInfo != null || phase != from) return false;
            phase = to;
            return true;
        }

        @Override
        public boolean close(CloseInfo info) {
            check();
            return markClose(Objects.requireNonNull(info));
        }

        @Override
        public boolean closeIf(P expected, CloseInfo info) {
            check();
            Objects.requireNonNull(info);
            return closeInfo == null && phase == expected && markClose(info);
        }

        @Override
        public Socket register(Socket s) {
            check();
            Objects.requireNonNull(s);
            if (closeInfo != null) {
                deferred.add(() -> closeQuietly(s));
                throw new ClosedSessionException("session closed");
            }
            sockets.add(s);
            return s;
        }

        @Override
        public boolean setGracefulStep(Function<CloseInfo, CompletableFuture<?>> step) {
            check();
            Objects.requireNonNull(step);
            if (closeInfo != null) return false;
            gracefulStep = step;
            return true;
        }

        @Override
        public void after(Runnable action) {
            check();
            deferred.add(Objects.requireNonNull(action));
        }
    }

    /** Caller holds the lock. Queues the close procedure as the last deferred action so far. */
    private boolean markClose(CloseInfo info) {
        if (closeInfo != null) return false;
        closeInfo = info;
        phase = closingPhase;
        long startedAt = nanoClock.getAsLong();
        closeStartedAt = startedAt;
        // First in line: a slow earlier action of this transaction must not delay the forced deadline.
        deferred.add(0, () -> runCloseProcedure(info, startedAt));
        return true;
    }

    // ---- conveniences ----------------------------------------------------------------------

    public P phase() {
        return atomically(Tx::phase);
    }

    public boolean isClosed() {
        return atomically(Tx::isClosed);
    }

    public Optional<CloseInfo> closeInfo() {
        return atomically(tx -> Optional.ofNullable(closeInfo));
    }

    public boolean close(CloseInfo info) {
        return atomically(tx -> tx.close(info));
    }

    public boolean closeIf(P expected, CloseInfo info) {
        return atomically(tx -> tx.closeIf(expected, info));
    }

    public Socket register(Socket s) {
        return atomically(tx -> tx.register(s));
    }

    /** Runs in phase A of the close procedure; registered after close, it runs immediately. */
    public void addCloseHook(Runnable hook) {
        Objects.requireNonNull(hook);
        atomically(tx -> {
            if (closeInfo != null) tx.after(hook);
            else closeHooks.add(hook);
            return null;
        });
    }

    /** Runs after sockets are closed and workers interrupted; registered late, it runs immediately. */
    public void addStopHook(Runnable hook) {
        Objects.requireNonNull(hook);
        atomically(tx -> {
            if (stopPhaseStarted) tx.after(hook);
            else stopHooks.add(hook);
            return null;
        });
    }

    public CompletableFuture<CloseInfo> socketsClosed() {
        return socketsClosed;
    }

    public CompletableFuture<CloseInfo> terminated() {
        return terminated;
    }

    boolean isLockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    // ---- workers ---------------------------------------------------------------------------

    /**
     * Starts a daemon worker unless the session is closed. The slot is reserved under the lock and
     * the thread is created and started outside it. If the session closes before the thread runs,
     * the body is skipped.
     */
    public boolean startWorker(String name, Runnable body) {
        return startWorker(name, body, () -> {});
    }

    /** As {@link #startWorker(String, Runnable)}; {@code onSkipped} runs outside the lock if the body is skipped. */
    public boolean startWorker(String name, Runnable body, Runnable onSkipped) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(body);
        Objects.requireNonNull(onSkipped);
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("startWorker must not be called while holding the session lock");
        }
        lock.lock();
        try {
            if (closeInfo != null) return false;
            workerCount++;
        } finally {
            lock.unlock();
        }
        try {
            Thread t = threadFactory.newThread(() -> runWorker(body, onSkipped));
            t.setName(name);
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "cannot start worker " + name, e);
            releaseWorker(null);
            close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "cannot start worker", true));
            return false;
        }
        return true;
    }

    private void runWorker(Runnable body, Runnable onSkipped) {
        Thread me = Thread.currentThread();
        boolean run;
        lock.lock();
        try {
            run = closeInfo == null;
            if (run) runningWorkers.add(me);
        } finally {
            lock.unlock();
        }
        try {
            if (run) body.run();
            else runQuietly(onSkipped); // closed before the thread ran: tell the owner, outside the lock
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "worker failed", e);
            close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "worker failed", true));
        } finally {
            releaseWorker(me);
        }
    }

    private void releaseWorker(Thread runningThread) {
        lock.lock();
        try {
            if (runningThread != null) runningWorkers.remove(runningThread);
            workerCount--;
        } finally {
            lock.unlock();
        }
        checkTerminated();
    }

    /** Workers that are STARTING (reserved) or running. */
    public int liveWorkers() {
        return locked(() -> workerCount);
    }

    private void checkTerminated() {
        CloseInfo info = locked(() -> stopHooksDone && workerCount == 0 ? closeInfo : null);
        if (info != null) terminated.complete(info);
    }

    // ---- close procedure -------------------------------------------------------------------

    private void runCloseProcedure(CloseInfo info, long startedAt) {
        long deadline = startedAt + closeGrace.toNanos();
        ScheduledFuture<?> forced = null;
        try {
            long delay = Math.max(0, deadline - nanoClock.getAsLong());
            forced = scheduler.schedule(() -> onDeadline(info), delay, TimeUnit.NANOSECONDS);
        } catch (Throwable e) { // rejected: nothing else guarantees the deadline, close right away
            forceCloseSockets(info);
        }
        for (Runnable hook : locked(() -> new ArrayList<>(closeHooks))) runQuietly(hook);

        CompletableFuture<?> step;
        try {
            Function<CloseInfo, CompletableFuture<?>> fn = locked(() -> gracefulStep);
            // a blocking hook may have outlived the deadline: the close is already finished then
            CompletableFuture<?> f = fn == null || finished.get() ? null : fn.apply(info);
            step = f != null ? f : CompletableFuture.completedFuture(null);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "graceful close step failed", e);
            step = CompletableFuture.failedFuture(e);
        }

        CompletableFuture<?> pending = step;
        ScheduledFuture<?> forcedTask = forced;
        try {
            cleanup.execute(() -> phaseB(info, pending, deadline, forcedTask));
        } catch (Throwable e) { // rejected: finish on this thread without waiting for the step
            finishClose(info, forcedTask);
        }
    }

    private void phaseB(CloseInfo info, CompletableFuture<?> step, long deadline, ScheduledFuture<?> forced) {
        try {
            long remaining = deadline - nanoClock.getAsLong();
            if (remaining > 0) step.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // timeout or failed step: proceed to close
        } finally {
            finishClose(info, forced);
        }
    }

    /**
     * Forced deadline: closes the raw sockets on the scheduler thread (cheap, must be punctual),
     * then finishes the rest on {@code cleanup} so a blocking stop hook cannot stall the scheduler.
     * This is what guarantees termination even if phase A or phase B never gets to run.
     */
    private void onDeadline(CloseInfo info) {
        forceCloseSockets(info);
        try {
            cleanup.execute(() -> finishClose(info, null));
        } catch (Throwable e) {
            finishClose(info, null);
        }
    }

    /** Idempotent: the forced deadline and phase B race to finish, exactly one wins. */
    private void finishClose(CloseInfo info, ScheduledFuture<?> forced) {
        if (forced != null) forced.cancel(false);
        if (!finished.compareAndSet(false, true)) return;
        try {
            forceCloseSockets(info);
            List<Thread> toInterrupt = locked(() -> new ArrayList<>(runningWorkers));
            for (Thread t : toInterrupt) {
                try {
                    t.interrupt();
                } catch (Throwable e) {
                    LOG.log(System.Logger.Level.WARNING, "interrupt failed", e);
                }
            }
            List<Runnable> hooks = locked(() -> {
                stopPhaseStarted = true;
                return new ArrayList<>(stopHooks);
            });
            for (Runnable hook : hooks) runQuietly(hook);
        } finally {
            lock.lock();
            try {
                stopHooksDone = true;
            } finally {
                lock.unlock();
            }
            checkTerminated();
        }
    }

    /** Closes every registered raw socket (idempotent), then completes {@link #socketsClosed()}. */
    private void forceCloseSockets(CloseInfo info) {
        try {
            for (Socket s : locked(() -> new ArrayList<>(sockets))) closeQuietly(s);
        } finally {
            socketsClosed.complete(info);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private <T> T locked(Supplier<T> read) {
        lock.lock();
        try {
            return read.get();
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Throwable e) { // incl. RuntimeException: one bad socket must not skip the rest
            LOG.log(System.Logger.Level.DEBUG, "socket close failed", e);
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "session action failed", e);
        }
    }

    private static void runAll(List<Runnable> actions) {
        for (Runnable a : actions) runQuietly(a);
    }
}
