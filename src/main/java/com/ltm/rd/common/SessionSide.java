package com.ltm.rd.common;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Hooks for one role, invoked by its session shell. */
public interface SessionSide {
    void onAuthenticated(SideTx stx);
    boolean readyForActive();
    void onPacket(SideTx stx, Packet p);
    boolean onActive(SideTx stx, long nowNanos, Channel img);
    CompletableFuture<Void> onClosing();

    /** Completes the shell's stable release target only after the role's closing stage settles. */
    static void bridgeClosing(SessionSide side, CompletableFuture<Void> target) {
        bridgeClosing(side::onClosing, target);
    }

    /** Allows the source stage to include release seams without replacing the shell's target. */
    static void bridgeClosing(Supplier<CompletableFuture<Void>> source, CompletableFuture<Void> target) {
        try {
            Objects.requireNonNull(source.get(), "closing future").whenComplete((v, e) -> {
                if (e != null) target.completeExceptionally(e);
                else target.complete(null);
            });
        } catch (Throwable e) {
            target.completeExceptionally(e);
        }
    }
}
