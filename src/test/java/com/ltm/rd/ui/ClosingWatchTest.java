package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClosingWatchTest {
    private final FakeClock clock = new FakeClock();
    private final ClosingWatch watch = new ClosingWatch(clock);

    @Test
    void warnsOnlyAfterTwoSecondsInClosing() {
        Snapshot closing = ClientFormStateTest.client(UiPhase.CLOSING, "");
        assertFalse(watch.warning(closing));
        clock.advance(Duration.ofMillis(1999));
        assertFalse(watch.warning(closing));
        clock.advance(Duration.ofMillis(1));
        assertTrue(watch.warning(closing));
    }

    @Test
    void leavingClosingResetsTheClock() {
        Snapshot closing = ClientFormStateTest.client(UiPhase.CLOSING, "");
        watch.warning(closing);
        clock.advance(Duration.ofSeconds(5));
        assertFalse(watch.warning(ClientFormStateTest.client(UiPhase.IDLE, "")));
        assertFalse(watch.warning(closing), "a new closing starts counting again");
        clock.advance(Duration.ofSeconds(2));
        assertTrue(watch.warning(closing));
    }
}
