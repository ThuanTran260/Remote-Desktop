package com.ltm.rd.common;

import java.util.concurrent.CompletableFuture;

/** Hooks for one role, invoked by its session shell. */
public interface SessionSide {
    void onAuthenticated(SideTx stx);
    boolean readyForActive();
    void onPacket(SideTx stx, Packet p);
    boolean onActive(SideTx stx, long nowNanos, Channel img);
    CompletableFuture<Void> onClosing();
}
