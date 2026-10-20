package com.ltm.rd.common;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WriteWatchdogTest {
    @Test
    void watchdogFiresOnceAndNeverWhenIdle() {
        WriteTracker tracker = new WriteTracker();
        AtomicInteger stuck = new AtomicInteger();
        WriteWatchdog dog = new WriteWatchdog(tracker, Duration.ofSeconds(5), stuck::incrementAndGet);

        dog.tick(s(100));
        assertEquals(0, stuck.get(), "idle tracker never fires");

        tracker.begin(s(10));
        dog.tick(s(15) - 1);
        assertEquals(0, stuck.get());
        dog.tick(s(15));
        assertEquals(1, stuck.get());
        dog.tick(s(30));
        assertEquals(1, stuck.get(), "fires at most once");
    }

    @Test
    void finishedWriteIsNotReported() {
        WriteTracker tracker = new WriteTracker();
        AtomicInteger stuck = new AtomicInteger();
        WriteWatchdog dog = new WriteWatchdog(tracker, Duration.ofSeconds(5), stuck::incrementAndGet);
        tracker.end(tracker.begin(0));
        dog.tick(s(60));
        assertEquals(0, stuck.get());
    }
}
