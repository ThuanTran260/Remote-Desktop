package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class SessionLifecycleTest {
    private static final int WAIT_S = 10;

    enum Phase { RESERVED, ACTIVE, CLOSING }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("test-sched"));
    private final ExecutorService cleanup = Executors.newCachedThreadPool(daemon("test-cleanup"));
    private final List<Socket> sockets = new ArrayList<>();
    private final List<ServerSocket> servers = new ArrayList<>();

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @AfterEach
    void cleanup() throws Exception {
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        for (Socket s : sockets) s.close();
        for (ServerSocket s : servers) s.close();
    }

    private static CloseInfo info(String message) {
        return new CloseInfo(Packet.DisconnectReason.USER, message, false);
    }

    private SessionLifecycle<Phase> lc(Duration grace) {
        return new SessionLifecycle<>(Phase.RESERVED, Phase.CLOSING, System::nanoTime, grace, scheduler, cleanup);
    }

    private SessionLifecycle<Phase> lc() {
        return lc(Duration.ofMillis(100));
    }

    private Socket unconnected() {
        Socket s = new Socket();
        sockets.add(s);
        return s;
    }

    /** Connected loopback pair; returns the client side (the server side is kept open and idle). */
    private Socket connected() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        servers.add(server);
        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        sockets.add(client);
        sockets.add(server.accept());
        return client;
    }

    /** Avoids the assertTrue(BooleanSupplier) overload ambiguity with a generic atomically. */
    private static boolean txBool(SessionLifecycle<Phase> lc,
            java.util.function.Function<SessionLifecycle.Tx<Phase>, Boolean> body) {
        return lc.atomically(body);
    }

    /** Polls (bounded) until {@code t} reaches {@code state}; proves a thread is blocked, not just started. */
    private static void awaitState(Thread t, Thread.State state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (t.getState() != state) {
            assertTrue(System.nanoTime() < deadline, "thread never reached " + state + ", is " + t.getState());
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
    }

    private static void await(CountDownLatch l) throws InterruptedException {
        assertTrue(l.await(WAIT_S, TimeUnit.SECONDS), "latch timed out");
    }

    // ---- close semantics -------------------------------------------------------------------

    @Test
    void closeIsIdempotentAndFirstInfoWins() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        AtomicInteger hooks = new AtomicInteger();
        lc.addCloseHook(hooks::incrementAndGet);
        CloseInfo first = info("first");
        assertTrue(lc.close(first));
        assertFalse(lc.close(info("second")));
        assertEquals(first, lc.closeInfo().orElseThrow());
        assertTrue(lc.isClosed());
        assertEquals(Phase.CLOSING, lc.phase());
        assertEquals(first, lc.terminated().get(WAIT_S, TimeUnit.SECONDS));
        assertEquals(1, hooks.get());
    }

    @Test
    void closeIfWrongPhaseDoesNothing() {
        SessionLifecycle<Phase> lc = lc();
        assertFalse(lc.closeIf(Phase.ACTIVE, info("x")));
        assertFalse(lc.isClosed());
        assertEquals(Phase.RESERVED, lc.phase());
        assertTrue(lc.closeIf(Phase.RESERVED, info("y")));
        assertTrue(lc.isClosed());
    }

    @Test
    void advanceBeforeCloseIfKeepsSessionOpen() {
        SessionLifecycle<Phase> lc = lc();
        assertTrue(txBool(lc, tx -> tx.advance(Phase.RESERVED, Phase.ACTIVE)));
        assertFalse(lc.closeIf(Phase.RESERVED, info("late")));
        assertEquals(Phase.ACTIVE, lc.phase());
        assertFalse(lc.isClosed());
    }

    @Test
    void closeIfBeforeAdvanceBlocksAdvance() {
        SessionLifecycle<Phase> lc = lc();
        assertTrue(lc.closeIf(Phase.RESERVED, info("early")));
        assertFalse(txBool(lc, tx -> tx.advance(Phase.RESERVED, Phase.ACTIVE)));
        assertEquals(Phase.CLOSING, lc.phase());
        assertTrue(lc.isClosed());
    }

    @Test
    void closeIfRacingAdvance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            for (int i = 0; i < 1000; i++) {
                SessionLifecycle<Phase> lc = lc(Duration.ofMillis(20));
                Future<Boolean> adv = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return lc.atomically(tx -> tx.advance(Phase.RESERVED, Phase.ACTIVE));
                });
                Future<Boolean> cls = pool.submit(() -> {
                    barrier.await(WAIT_S, TimeUnit.SECONDS);
                    return lc.closeIf(Phase.RESERVED, info("race"));
                });
                boolean advanced = adv.get(WAIT_S, TimeUnit.SECONDS);
                boolean closed = cls.get(WAIT_S, TimeUnit.SECONDS);
                assertTrue(advanced ^ closed, "iteration " + i);
                assertEquals(advanced ? Phase.ACTIVE : Phase.CLOSING, lc.phase());
                assertEquals(closed, lc.isClosed());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- atomically contract ---------------------------------------------------------------

    @Test
    void closeInsideNestedAtomicallyDefersAllSideEffects() {
        SessionLifecycle<Phase> lc = lc();
        AtomicBoolean hookRan = new AtomicBoolean();
        AtomicBoolean heldInHook = new AtomicBoolean(true);
        lc.addCloseHook(() -> {
            hookRan.set(true);
            heldInHook.set(lc.isLockHeldByCurrentThread());
        });
        lc.atomically(outer -> {
            lc.atomically(inner -> inner.close(info("nested")));
            assertFalse(hookRan.get(), "hook must wait for the outermost release");
            assertTrue(lc.isLockHeldByCurrentThread());
            assertTrue(outer.isClosed());
            return null;
        });
        assertTrue(hookRan.get());
        assertFalse(heldInHook.get());
        assertFalse(lc.isLockHeldByCurrentThread());
    }

    @Test
    void afterActionsRunOutsideLockInOrder() {
        SessionLifecycle<Phase> lc = lc();
        List<String> order = new ArrayList<>();
        List<Boolean> held = new ArrayList<>();
        lc.atomically(tx -> {
            tx.after(() -> {
                order.add("a");
                held.add(lc.isLockHeldByCurrentThread());
            });
            lc.atomically(inner -> {
                inner.after(() -> {
                    order.add("b");
                    held.add(lc.isLockHeldByCurrentThread());
                });
                return null;
            });
            tx.after(() -> {
                order.add("c");
                held.add(lc.isLockHeldByCurrentThread());
            });
            assertTrue(order.isEmpty());
            return null;
        });
        assertEquals(List.of("a", "b", "c"), order);
        assertEquals(List.of(false, false, false), held);
    }

    @Test
    void registerAfterCloseClosesSocketAndThrows() {
        SessionLifecycle<Phase> lc = lc();
        lc.close(info("closed"));
        Socket s = unconnected();
        assertThrows(ClosedSessionException.class, () -> lc.register(s));
        assertTrue(s.isClosed());
    }

    @Test
    void setGracefulStepAfterCloseReturnsFalseAndIsNeverInvoked() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        AtomicInteger invoked = new AtomicInteger();
        lc.close(info("closed"));
        assertFalse(txBool(lc, tx -> tx.setGracefulStep(i -> {
            invoked.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        })));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, invoked.get());
    }

    @Test
    void gracefulStepReceivesInfoAndSocketsCloseAfterItCompletes() throws Exception {
        SessionLifecycle<Phase> lc = lc(Duration.ofSeconds(30));
        Socket s = unconnected();
        lc.register(s);
        CompletableFuture<Object> step = new CompletableFuture<>();
        AtomicReference<CloseInfo> seen = new AtomicReference<>();
        assertTrue(txBool(lc, tx -> tx.setGracefulStep(i -> {
            seen.set(i);
            return step;
        })));
        CloseInfo bye = new CloseInfo(Packet.DisconnectReason.USER, "bye", true);
        lc.close(bye);
        assertEquals(bye, seen.get());
        assertFalse(lc.socketsClosed().isDone(), "sockets stay open while the graceful step is pending");
        assertFalse(s.isClosed());
        step.complete(null);
        assertEquals(bye, lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS));
        assertTrue(s.isClosed());
    }

    // ---- two-phase close -------------------------------------------------------------------

    @Test
    void throwingHookStillClosesSocketsByDeadline() throws Exception {
        SessionLifecycle<Phase> lc = lc(Duration.ofMillis(200));
        Socket s = unconnected();
        lc.register(s);
        AtomicInteger laterHooks = new AtomicInteger();
        AtomicInteger throwingInvoked = new AtomicInteger();
        lc.addCloseHook(() -> {
            throwingInvoked.incrementAndGet();
            throw new IllegalStateException("hook boom");
        });
        lc.addCloseHook(laterHooks::incrementAndGet);
        lc.atomically(tx -> tx.setGracefulStep(i -> new CompletableFuture<>())); // never completes
        long start = System.nanoTime();
        lc.close(info("x"));
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 200, "never close before the deadline: " + elapsedMs);
        assertTrue(elapsedMs <= 800, "closed within deadline + slack: " + elapsedMs);
        assertTrue(s.isClosed());
        assertEquals(1, throwingInvoked.get(), "the throwing hook must really have run");
        assertEquals(1, laterHooks.get(), "a throwing hook must not skip later hooks");
    }

    @Test
    void throwingSocketCloseDoesNotSkipLaterSocketsOrStopHooks() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket exploding = new Socket() {
            @Override
            public synchronized void close() {
                throw new IllegalStateException("close boom");
            }
        };
        Socket good = unconnected();
        lc.register(exploding);
        lc.register(good);
        CountDownLatch stopRan = new CountDownLatch(1);
        lc.addStopHook(stopRan::countDown);
        assertDoesNotThrow(() -> lc.close(info("x")));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(good.isClosed(), "a failing close must not leave later sockets open");
        assertEquals(0, stopRan.getCount(), "stop hooks must still run");
        assertTrue(lc.socketsClosed().isDone());
    }

    @Test
    void blockingCloseHookDoesNotPreventDeadlineFinish() throws Exception {
        SessionLifecycle<Phase> lc = lc(Duration.ofMillis(200));
        Socket s = unconnected();
        lc.register(s);
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch hookRelease = new CountDownLatch(1);
        CountDownLatch stopRan = new CountDownLatch(1);
        lc.addCloseHook(() -> {
            hookEntered.countDown();
            try {
                hookRelease.await();
            } catch (InterruptedException ignored) {
                // released at the end of the test
            }
        });
        lc.addStopHook(stopRan::countDown);
        long start = System.nanoTime();
        Thread closer = new Thread(() -> lc.close(info("x")));
        closer.setDaemon(true);
        closer.start();
        try {
            await(hookEntered);
            lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
            long socketsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(socketsMs >= 200 && socketsMs <= 800, "sockets closed by the deadline: " + socketsMs);
            await(stopRan);
            lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
            long terminatedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(terminatedMs <= 800, "terminated by the deadline: " + terminatedMs);
            assertTrue(s.isClosed());
        } finally {
            hookRelease.countDown();
            closer.join(WAIT_S * 1000L);
        }
    }

    @Test
    void blockingEarlierActionDoesNotDelayForcedDeadline() throws Exception {
        Duration grace = Duration.ofMillis(200);
        SessionLifecycle<Phase> lc = lc(grace);
        Socket s = unconnected();
        lc.register(s);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch actionRelease = new CountDownLatch(1);
        Thread t = new Thread(() -> lc.atomically(tx -> {
            tx.after(() -> {
                actionEntered.countDown();
                try {
                    actionRelease.await();
                } catch (InterruptedException ignored) {
                    // released at the end of the test
                }
            });
            tx.close(info("x"));
            return null;
        }));
        t.setDaemon(true);
        t.start();
        try {
            await(actionEntered);
            lc.socketsClosed().get(grace.toMillis() + 600, TimeUnit.MILLISECONDS);
            assertTrue(s.isClosed());
        } finally {
            actionRelease.countDown();
            t.join(WAIT_S * 1000L);
        }
    }

    @Test
    void writerStartedThroughLifecycleLearnsWhenWorkerIsSkipped() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadFactory gated = r -> {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new Thread(r);
        };
        SessionLifecycle<Phase> lc = new SessionLifecycle<>(Phase.RESERVED, Phase.CLOSING, System::nanoTime,
                Duration.ofSeconds(30), scheduler, cleanup, gated);
        CommandWriter w = new CommandWriter(new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()),
                t -> {});
        AtomicReference<CompletableFuture<Void>> writerDone = new AtomicReference<>();
        assertTrue(txBool(lc, tx -> tx.setGracefulStep(i -> {
            CompletableFuture<Void> f = w.closeGracefully(Packet.disconnect(Packet.DisconnectReason.USER, "bye"));
            writerDone.set(f);
            return f;
        })));
        Thread starter = new Thread(() -> w.start(lc::startWorker));
        starter.setDaemon(true);
        starter.start();
        await(reached);
        lc.close(info("x")); // closes between reservation and thread run
        release.countDown();
        starter.join(WAIT_S * 1000L);
        // far below the 30 s grace: the skipped writer must fail its close future
        lc.socketsClosed().get(5, TimeUnit.SECONDS);
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(writerDone.get().isCompletedExceptionally());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(1)));
    }

    @Test
    void throwingGracefulStepStillClosesSockets() throws Exception {
        SessionLifecycle<Phase> lc = lc(Duration.ofSeconds(30));
        Socket s = unconnected();
        lc.register(s);
        lc.atomically(tx -> tx.setGracefulStep(i -> {
            throw new IllegalStateException("step boom");
        }));
        lc.close(info("x"));
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS); // far below the 30 s grace
        assertTrue(s.isClosed());
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void socketsClosedBeforeStopHooks() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket s = unconnected();
        lc.register(s);
        AtomicBoolean socketClosedInHook = new AtomicBoolean();
        AtomicBoolean futureDoneInHook = new AtomicBoolean();
        lc.addStopHook(() -> {
            socketClosedInHook.set(s.isClosed());
            futureDoneInHook.set(lc.socketsClosed().isDone());
        });
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(socketClosedInHook.get());
        assertTrue(futureDoneInHook.get());
    }

    @Test
    void lateHooksRunImmediately() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        AtomicInteger ran = new AtomicInteger();
        lc.addCloseHook(ran::incrementAndGet);
        lc.addStopHook(ran::incrementAndGet);
        assertEquals(2, ran.get());
    }

    @Test
    void rejectedSchedulerAndCleanupStillCloseSockets() throws Exception {
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        SessionLifecycle<Phase> lc = lc(Duration.ofSeconds(30));
        Socket s = unconnected();
        lc.register(s);
        lc.close(info("x"));
        assertTrue(s.isClosed(), "closed inline on the calling thread");
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void terminatedWaitsForWorkers() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket s = connected();
        lc.register(s);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch exitGate = new CountDownLatch(1);
        CountDownLatch stopHookRan = new CountDownLatch(1);
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        assertTrue(lc.startWorker("rd-test-reader", () -> {
            worker.set(Thread.currentThread());
            try {
                reading.countDown();
                s.getInputStream().read(); // the idle peer never writes: only close can end this read
            } catch (Throwable e) {
                readFailure.set(e);
            }
            while (true) {
                try {
                    exitGate.await();
                    return;
                } catch (InterruptedException ignored) {
                    // interrupted by the close procedure; keep waiting for the gate
                }
            }
        }));
        await(reading);
        assertEquals(1, lc.liveWorkers());
        assertFalse(lc.terminated().isDone());
        lc.addStopHook(stopHookRan::countDown);
        lc.close(info("x"));
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        await(stopHookRan);
        awaitState(worker.get(), Thread.State.WAITING); // read failed, worker provably parked on the gate
        assertFalse(lc.terminated().isDone(), "worker still running");
        exitGate.countDown();
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, lc.liveWorkers());
        assertInstanceOf(SocketException.class, readFailure.get());
    }

    // ---- startWorker -----------------------------------------------------------------------

    @Test
    void startWorkerRefusedAfterClose() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        lc.close(info("x"));
        AtomicBoolean ran = new AtomicBoolean();
        assertFalse(lc.startWorker("rd-test", () -> ran.set(true)));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertFalse(ran.get());
        assertEquals(0, lc.liveWorkers());
    }

    @Test
    void startWorkerInsideLockThrows() {
        SessionLifecycle<Phase> lc = lc();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        lc.atomically(tx -> {
            try {
                lc.startWorker("rd-test", () -> {});
            } catch (IllegalStateException e) {
                thrown.set(e);
            }
            return null;
        });
        assertNotNull(thrown.get());
        assertEquals(0, lc.liveWorkers());
        assertFalse(lc.isClosed());
    }

    @Test
    void startWorkerCreatesNamedDaemonThread() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(lc.startWorker("rd-test-worker", () -> {
            thread.set(Thread.currentThread());
            ran.countDown();
        }));
        await(ran);
        assertEquals("rd-test-worker", thread.get().getName());
        assertTrue(thread.get().isDaemon());
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
    }

    @Test
    void throwingWorkerBodyClosesSessionAndReleasesSlot() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        assertTrue(lc.startWorker("rd-test", () -> {
            throw new IllegalStateException("worker boom");
        }));
        CloseInfo closed = lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, closed.reason());
        assertEquals(0, lc.liveWorkers());
    }

    @Test
    void commandWriterStartsThroughLifecycle() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        CommandWriter w = new CommandWriter(new java.io.DataOutputStream(bytes), t -> {});
        assertTrue(w.start(lc::startWorker));
        assertEquals(1, lc.liveWorkers());
        lc.addStopHook(w::stop);
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertEquals(0, lc.liveWorkers());
        assertEquals(CommandWriter.OfferResult.CLOSED, w.offer(Packet.ping(1)));

        SessionLifecycle<Phase> closed = lc();
        closed.close(info("x"));
        CommandWriter late = new CommandWriter(new java.io.DataOutputStream(bytes), t -> {});
        assertFalse(late.start(closed::startWorker));
        assertEquals(CommandWriter.OfferResult.CLOSED, late.offer(Packet.ping(1)));
    }

    @Test
    void closeBetweenReservationAndThreadStart() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadFactory gated = r -> {
            reserved.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new Thread(r);
        };
        SessionLifecycle<Phase> lc = new SessionLifecycle<>(Phase.RESERVED, Phase.CLOSING, System::nanoTime,
                Duration.ofMillis(100), scheduler, cleanup, gated);
        AtomicBoolean ran = new AtomicBoolean();
        Thread starter = new Thread(() -> lc.startWorker("rd-test", () -> ran.set(true)));
        starter.setDaemon(true);
        starter.start();
        await(reserved);
        assertEquals(1, lc.liveWorkers(), "slot is reserved before the thread exists");
        lc.close(info("x"));
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertFalse(lc.terminated().isDone(), "reservation keeps the session unterminated");
        release.countDown();
        starter.join(WAIT_S * 1000L);
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertFalse(ran.get(), "body must be skipped when closed before the thread ran");
        assertEquals(0, lc.liveWorkers());
    }

    @Test
    void threadStartFailureReleasesReservation() throws Exception {
        ThreadFactory failing = r -> new Thread(r) {
            @Override
            public synchronized void start() {
                throw new IllegalStateException("cannot start");
            }
        };
        SessionLifecycle<Phase> lc = new SessionLifecycle<>(Phase.RESERVED, Phase.CLOSING, System::nanoTime,
                Duration.ofMillis(100), scheduler, cleanup, failing);
        assertFalse(lc.startWorker("rd-test", () -> {}));
        assertEquals(0, lc.liveWorkers());
        assertTrue(lc.isClosed());
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lc.closeInfo().orElseThrow().reason());
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
    }

    // ---- exceptions in the body ------------------------------------------------------------

    @Test
    void bodyThrowsAfterRegisterClosesSessionAndSocket() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket s = unconnected();
        AtomicBoolean afterRan = new AtomicBoolean();
        IllegalArgumentException boom = new IllegalArgumentException("body boom");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> lc.atomically(tx -> {
            tx.register(s);
            tx.after(() -> afterRan.set(true));
            throw boom;
        }));
        assertSame(boom, thrown);
        assertTrue(afterRan.get(), "actions registered before the throw still run");
        assertTrue(lc.isClosed());
        CloseInfo closed = lc.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, closed.reason());
        assertEquals("internal error", closed.message());
        lc.socketsClosed().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(s.isClosed());
        assertFalse(lc.isLockHeldByCurrentThread());
    }

    @Test
    void bodyThrowsAfterCloseStillRunsCloseProcedure() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket s = unconnected();
        lc.register(s);
        AtomicInteger hooks = new AtomicInteger();
        lc.addCloseHook(hooks::incrementAndGet);
        CloseInfo mine = info("mine");
        assertThrows(IllegalStateException.class, () -> lc.atomically(tx -> {
            tx.close(mine);
            throw new IllegalStateException("after close");
        }));
        assertEquals(mine, lc.closeInfo().orElseThrow(), "first close wins over the internal error");
        assertEquals(1, hooks.get());
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(s.isClosed());
    }

    @Test
    void throwingActionDoesNotSkipLaterActions() {
        SessionLifecycle<Phase> lc = lc();
        List<String> order = new ArrayList<>();
        lc.atomically(tx -> {
            tx.after(() -> order.add("a"));
            tx.after(() -> {
                throw new IllegalStateException("action boom");
            });
            tx.after(() -> order.add("c"));
            return null;
        });
        assertEquals(List.of("a", "c"), order);
        assertFalse(lc.isClosed(), "an action failure is isolated, not a session error");
    }

    @Test
    void txUsedOutsideCallbackThrows() {
        SessionLifecycle<Phase> lc = lc();
        AtomicReference<SessionLifecycle.Tx<Phase>> saved = new AtomicReference<>();
        lc.atomically(tx -> {
            saved.set(tx);
            return null;
        });
        SessionLifecycle.Tx<Phase> tx = saved.get();
        assertThrows(IllegalStateException.class, tx::phase);
        assertThrows(IllegalStateException.class, tx::isClosed);
        assertThrows(IllegalStateException.class, () -> tx.advance(Phase.RESERVED, Phase.ACTIVE));
        assertThrows(IllegalStateException.class, () -> tx.close(info("x")));
        assertThrows(IllegalStateException.class, () -> tx.after(() -> {}));
        assertThrows(IllegalStateException.class, () -> tx.register(new Socket()));
        assertFalse(lc.isClosed());
    }

    // ---- interrupt ordering ----------------------------------------------------------------

    @Test
    void interruptPrecedesWrapperClose() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        CountDownLatch running = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean hookDone = new AtomicBoolean();
        AtomicBoolean interruptedInHook = new AtomicBoolean();
        assertTrue(lc.startWorker("rd-test", () -> {
            worker.set(Thread.currentThread());
            running.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
            // spin without consuming the interrupt flag, so the hook can still observe it
            while (!hookDone.get() && System.nanoTime() < deadline) Thread.onSpinWait();
        }));
        await(running);
        lc.addStopHook(() -> {
            interruptedInHook.set(worker.get().isInterrupted());
            hookDone.set(true);
        });
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(interruptedInHook.get(), "workers are interrupted before stop hooks close wrappers");
    }

    @Test
    void blockedWriterReleasedByInterrupt() throws Exception {
        SessionLifecycle<Phase> lc = lc();
        Socket s = unconnected();
        lc.register(s);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean wrapperClosed = new AtomicBoolean();
        AtomicBoolean releasedByInterrupt = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        assertTrue(lc.startWorker("rd-test-writer", () -> {
            try {
                worker.set(Thread.currentThread());
                blocked.countDown();
                new CountDownLatch(1).await(); // stands in for a writer throttled while holding a wrapper
            } catch (InterruptedException e) {
                releasedByInterrupt.set(true);
            }
        }));
        await(blocked);
        awaitState(worker.get(), Thread.State.WAITING);
        assertFalse(lc.terminated().isDone(), "worker is blocked, session cannot be terminated");
        assertEquals(1, lc.liveWorkers());
        lc.addStopHook(() -> wrapperClosed.set(true));
        lc.close(info("x"));
        lc.terminated().get(WAIT_S, TimeUnit.SECONDS);
        assertTrue(releasedByInterrupt.get());
        assertTrue(wrapperClosed.get());
        assertTrue(s.isClosed());
        assertEquals(0, lc.liveWorkers());
    }
}
