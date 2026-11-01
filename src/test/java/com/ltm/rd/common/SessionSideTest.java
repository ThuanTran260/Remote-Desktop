package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SessionSideTest {
    private static SessionSide side(Supplier<CompletableFuture<Void>> closing) {
        return new SessionSide() {
            @Override public void onAuthenticated(SideTx stx) { }
            @Override public boolean readyForActive() { return true; }
            @Override public void onPacket(SideTx stx, Packet p) { }
            @Override public boolean onActive(SideTx stx, long now, Channel img) { return true; }
            @Override public CompletableFuture<Void> onClosing() { return closing.get(); }
        };
    }

    @Test void bridgeKeepsTargetAndWaitsForAHeldFuture() {
        CompletableFuture<Void> source = new CompletableFuture<>();
        CompletableFuture<Void> target = new CompletableFuture<>();
        CompletableFuture<Void> original = target;
        SessionSide.bridgeClosing(side(() -> source), target);
        assertSame(original, target);
        assertFalse(target.isDone());
        source.complete(null);
        assertSame(original, target);
        assertTrue(target.isDone());
        assertFalse(target.isCompletedExceptionally());
    }

    @Test void bridgeFailsTargetOnSynchronousThrow() {
        IllegalStateException failure = new IllegalStateException("closing failed");
        CompletableFuture<Void> target = new CompletableFuture<>();
        SessionSide.bridgeClosing(side(() -> { throw failure; }), target);
        assertTrue(target.isCompletedExceptionally());
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, target::join).getCause());
    }

    @Test void bridgeFailsTargetOnFailedFuture() {
        IllegalStateException failure = new IllegalStateException("release failed");
        CompletableFuture<Void> target = new CompletableFuture<>();
        SessionSide.bridgeClosing(side(() -> CompletableFuture.failedFuture(failure)), target);
        assertTrue(target.isCompletedExceptionally());
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, target::join).getCause());
    }

    @Test void bridgeFailsTargetOnNullFuture() {
        CompletableFuture<Void> target = new CompletableFuture<>();
        SessionSide.bridgeClosing(side(() -> null), target);
        assertTrue(target.isCompletedExceptionally());
        assertInstanceOf(NullPointerException.class,
                assertThrows(java.util.concurrent.CompletionException.class, target::join).getCause());
    }

    @Test void bridgeSupplierAppliesSourceTransformBeforeCompletingTarget() throws Exception {
        CompletableFuture<Void> source = new CompletableFuture<>();
        CompletableFuture<Void> target = new CompletableFuture<>();
        CompletableFuture<Void> original = target;
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SessionSide.bridgeClosing(() -> source.thenRunAsync(() -> {
                reached.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, executor), target);
            assertFalse(target.isDone());
            source.complete(null);
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            assertFalse(target.isDone());
            assertSame(original, target);
            release.countDown();
            target.get(5, TimeUnit.SECONDS);
            assertSame(original, target);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
