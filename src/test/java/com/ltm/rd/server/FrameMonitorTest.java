package com.ltm.rd.server;

import static com.ltm.rd.common.FakeClock.s;
import static com.ltm.rd.server.FrameMonitor.Transition.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FrameMonitorTest {
    private static final Duration STALL_AFTER = Duration.ofSeconds(3);
    private static final Duration TIMEOUT_AFTER = Duration.ofSeconds(15);

    private static FrameMonitor monitor(long activeAt) {
        return new FrameMonitor(activeAt, STALL_AFTER, TIMEOUT_AFTER);
    }

    @Test
    void stallBoundaryFromActive() {
        FrameMonitor m = monitor(s(10));
        assertEquals(NONE, m.onTick(s(13) - 1));
        assertFalse(m.stalled());
        assertEquals(STALL_START, m.onTick(s(13)));
        assertTrue(m.stalled());
        assertEquals(NONE, m.onTick(s(14)), "a stall starts once");
        assertEquals(NONE, m.onTick(s(20)));
    }

    @Test
    void firstFrameBeforeStallEnablesWithoutStallEnd() {
        FrameMonitor m = monitor(0);
        assertFalse(m.hasFrame());
        assertEquals(NONE, m.onFrame(s(2)));
        assertTrue(m.hasFrame());
        assertFalse(m.stalled());
        assertEquals(NONE, m.onTick(s(5) - 1), "the stall window restarts at the frame");
        assertEquals(STALL_START, m.onTick(s(5)));
    }

    @Test
    void stallEndsOnNextFrame() {
        FrameMonitor m = monitor(0);
        assertEquals(STALL_START, m.onTick(s(3)));
        assertEquals(STALL_END, m.onFrame(s(4)));
        assertFalse(m.stalled());
        assertTrue(m.hasFrame());
        assertEquals(NONE, m.onFrame(s(4) + 1), "a second frame is not another transition");
    }

    @Test
    void frameAtTimeoutInstantAfterTickIsDropped() {
        FrameMonitor m = monitor(0);
        assertEquals(TIMEOUT, m.onTick(s(15)));
        assertTrue(m.timedOut());
        assertEquals(TIMEOUT, m.onFrame(s(15)), "the frame is dropped once timed out");
        assertFalse(m.hasFrame());
        assertEquals(NONE, m.onTick(s(16)), "the timeout is reported once by tick");
    }

    @Test
    void frameBeforeTimeoutInstantWinsOverLaterTick() {
        FrameMonitor m = monitor(0);
        assertEquals(NONE, m.onFrame(s(15))); // first frame at the instant: no tick decided yet
        assertEquals(NONE, m.onTick(s(15)));
        assertFalse(m.timedOut());
    }

    @Test
    void frameTimeoutUsesCurrentLastFrameAt() {
        FrameMonitor m = monitor(0);
        m.onFrame(s(10));
        assertEquals(STALL_START, m.onTick(s(13)));
        assertNotEquals(TIMEOUT, m.onTick(s(15)), "15 s after activation is not 15 s after the last frame");
        assertEquals(NONE, m.onTick(s(25) - 1));
        assertEquals(TIMEOUT, m.onTick(s(25)));
    }

    @Test
    void revisionIncrementsOncePerTransition() {
        FrameMonitor m = monitor(0);
        assertEquals(0, m.revision());
        m.onTick(s(1));
        assertEquals(0, m.revision(), "no transition, no revision");
        m.onTick(s(3));
        assertEquals(1, m.revision());
        m.onTick(s(4));
        assertEquals(1, m.revision());
        m.onFrame(s(5));
        assertEquals(2, m.revision());
        m.onFrame(s(6));
        assertEquals(2, m.revision());
        m.onTick(s(21));
        assertEquals(3, m.revision());
        m.onFrame(s(22));
        assertEquals(3, m.revision(), "a dropped frame is not a transition");
    }
}
