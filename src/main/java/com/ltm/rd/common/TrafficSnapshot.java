package com.ltm.rd.common;

/** Cumulative byte totals of the command and image channels of one session, as seen by this side. */
public record TrafficSnapshot(long cmdIn, long cmdOut, long imgIn, long imgOut) {
    public static final TrafficSnapshot ZERO = new TrafficSnapshot(0, 0, 0, 0);
}
