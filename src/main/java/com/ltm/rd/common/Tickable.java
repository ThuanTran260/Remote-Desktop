package com.ltm.rd.common;

/** A deadline check driven by {@link Ticker}; tests call {@code tick} directly with a fake time. */
@FunctionalInterface
public interface Tickable {
    void tick(long nowNanos);
}
