package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LifecyclePortTest {
    enum Phase { RESERVED, ACTIVE, CLOSING }

    private final AtomicLong clock = new AtomicLong(10);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SessionLifecycle<Phase> lifecycle = new SessionLifecycle<>(Phase.RESERVED, Phase.CLOSING,
            clock::get, Duration.ZERO, scheduler, Runnable::run);
    private final TestShell shell = new TestShell();
    private final LifecyclePort<Phase> port = port(shell);

    private final class TestShell implements LifecyclePort.Shell<Phase> {
        int activations;
        long checkedAt;
        CommandWriter writer;

        @Override public boolean isActive(Phase phase) { return phase == Phase.ACTIVE; }
        @Override public boolean isActivationPending(Phase phase) { return phase == Phase.RESERVED; }
        @Override public boolean activationExpired(long now) { checkedAt = now; return now >= 20; }
        @Override public CommandWriter writer() { return writer; }
        @Override public void tryActivate(SessionLifecycle.Tx<Phase> tx) { activations++; }
    }

    private LifecyclePort<Phase> port(LifecyclePort.Shell<Phase> target) {
        return new LifecyclePort<>(lifecycle, 37, null, clock::get, target);
    }

    @AfterEach void cleanup() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
        scheduler.shutdownNow();
    }

    @Test void sideTxMapsShellPhases() {
        port.atomically(tx -> {
            assertTrue(tx.isActivationPending());
            assertFalse(tx.isActive());
            lifecycle.atomically(raw -> raw.advance(Phase.RESERVED, Phase.ACTIVE));
            assertTrue(tx.isActive());
            assertFalse(tx.isActivationPending());
            return null;
        });
    }

    @Test void sideTxUsedAfterCallbackThrows() {
        SideTx saved = port.atomically(tx -> tx);
        assertThrows(IllegalStateException.class, saved::isClosed);
        assertThrows(IllegalStateException.class, saved::isActive);
        assertThrows(IllegalStateException.class, saved::isActivationPending);
        assertThrows(IllegalStateException.class, saved::activationExpired);
        assertThrows(IllegalStateException.class, () -> saved.after(() -> fail("expired action")));
        assertThrows(IllegalStateException.class,
                () -> saved.close(new CloseInfo(Packet.DisconnectReason.USER, "expired", false)));
    }

    @Test void tryActivateRejectsSideTxOfAnotherPort() {
        TestShell otherShell = new TestShell();
        LifecyclePort<Phase> other = port(otherShell);
        port.atomically(tx -> {
            assertThrows(IllegalArgumentException.class, () -> other.tryActivate(tx));
            assertThrows(IllegalArgumentException.class, () -> other.tryActivate(null));
            return null;
        });
        assertEquals(0, otherShell.activations);
    }

    @Test void tryActivateWithExpiredSideTxThrows() {
        SideTx saved = port.atomically(tx -> tx);
        assertThrows(IllegalStateException.class, () -> port.tryActivate(saved));
        assertEquals(0, shell.activations);
    }

    @Test void tryActivateRejectsSideTxOnAnotherThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        port.atomically(tx -> {
            Thread other = new Thread(() -> {
                try { port.tryActivate(tx); } catch (Throwable e) { failure.set(e); }
            });
            other.start();
            try { other.join(1000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(other.isAlive());
            return null;
        });
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, shell.activations);
    }

    @Test void tryActivateRunsShellInsideTheSameTx() {
        AtomicReference<SessionLifecycle.Tx<Phase>> seen = new AtomicReference<>();
        LifecyclePort<Phase> activating = port(new LifecyclePort.Shell<>() {
            @Override public boolean isActive(Phase phase) { return phase == Phase.ACTIVE; }
            @Override public boolean isActivationPending(Phase phase) { return phase == Phase.RESERVED; }
            @Override public boolean activationExpired(long now) { return false; }
            @Override public CommandWriter writer() { return null; }
            @Override public void tryActivate(SessionLifecycle.Tx<Phase> tx) {
                assertNull(seen.getAndSet(tx));
                assertTrue(tx.advance(Phase.RESERVED, Phase.ACTIVE));
            }
        });
        activating.atomically(tx -> { activating.tryActivate(tx); assertTrue(tx.isActive()); return null; });
        assertNotNull(seen.get());
        assertThrows(IllegalStateException.class, () -> seen.get().isClosed());
    }

    @Test void activationExpiredReadsClockAtCallTime() {
        port.atomically(tx -> {
            assertFalse(tx.activationExpired());
            clock.set(20);
            assertTrue(tx.activationExpired());
            assertEquals(20, shell.checkedAt);
            return null;
        });
    }

    @Test void offerIsClosedBeforeAWriterExists() {
        assertEquals(CommandWriter.OfferResult.CLOSED, port.offer(Packet.ping(1)));
    }

    @Test void registerWithoutTickerIsANoOp() {
        port.register(now -> fail("no ticker")).close();
    }

    @Test void startWorkerAfterCloseSkipsTheBody() {
        CloseInfo info = new CloseInfo(Packet.DisconnectReason.USER, "given reason", true);
        assertTrue(port.close(info));
        assertTrue(port.isClosed());
        assertEquals(info, lifecycle.closeInfo().orElseThrow());
        assertFalse(port.startWorker("unused", () -> fail("closed worker")));
    }

    @Test void afterInNestedAtomicallyRunsOnlyAfterOuterTx() {
        AtomicBoolean ran = new AtomicBoolean();
        lifecycle.atomically(raw -> {
            port.atomically(tx -> {
                tx.after(() -> {
                    assertFalse(lifecycle.isLockHeldByCurrentThread());
                    ran.set(true);
                });
                return null;
            });
            assertFalse(ran.get());
            return null;
        });
        assertTrue(ran.get());
    }
}
