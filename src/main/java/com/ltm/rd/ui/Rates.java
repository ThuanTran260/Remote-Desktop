package com.ltm.rd.ui;

/** Live rates shown in the status bar; computed from real elapsed time by {@link RateSampler}. */
public record Rates(double fps, double cmdKiBps, double imgKiBps, long rttNanos) {
    public static final Rates ZERO = new Rates(0, 0, 0, TrafficStats.NO_RTT);
}
