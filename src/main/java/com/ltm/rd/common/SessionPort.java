package com.ltm.rd.common;

import java.util.function.Function;

/** Lifecycle services shared by the two session roles. */
public interface SessionPort {
    <R> R atomically(Function<SideTx, R> body);
    long generation();
    boolean isClosed();
    boolean close(CloseInfo info);
    CommandWriter.OfferResult offer(Packet p);
    Ticker.Registration register(Tickable t);
    void addStopHook(Runnable hook);
    boolean startWorker(String name, Runnable body);
    void tryActivate(SideTx stx);
}
