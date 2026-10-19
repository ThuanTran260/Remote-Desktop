package com.ltm.rd.common;

/** Context-dependent checks that need the Client screen size from SCREEN_INFO. */
public final class Validation {
    private Validation() {}

    /** Pointer must lie inside the Client screen: 0 <= x < rw and 0 <= y < rh. */
    public static void checkPointer(int x, int y, int rw, int rh) {
        if (x < 0 || x >= rw || y < 0 || y >= rh) {
            throw new ProtocolException("pointer (" + x + "," + y + ") outside " + rw + "x" + rh);
        }
    }

    /**
     * FRAME header must fit the screen (MAX_FRAME_WIDTH is enforced context-free by Packet) and keep the screen aspect ratio
     * within one pixel of rounding.
     */
    public static void checkFrameHeader(int w, int h, int rw, int rh) {
        if (w < 1 || w > rw) {
            throw new ProtocolException("frame width " + w + " out of range (screen " + rw + ")");
        }
        if (h < 1 || h > rh) {
            throw new ProtocolException("frame height " + h + " out of range (screen " + rh + ")");
        }
        long expected = Math.round((double) w * rh / rw);
        if (Math.abs(h - expected) > 1) {
            throw new ProtocolException("frame " + w + "x" + h + " does not match screen ratio "
                    + rw + ":" + rh);
        }
    }
}
