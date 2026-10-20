package com.ltm.rd.ui;

/** Cumulative byte totals and the last round-trip time; rates are computed by the UI from these. */
public record TrafficStats(long cmdBytesIn, long cmdBytesOut, long imgBytesIn, long imgBytesOut, long rttNanos) {
    public static final long NO_RTT = -1L;
    public static final TrafficStats ZERO = new TrafficStats(0, 0, 0, 0, NO_RTT);
}
