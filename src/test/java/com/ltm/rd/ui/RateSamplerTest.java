package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.common.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateSamplerTest {
    private final FakeClock clock = new FakeClock();
    private final RateSampler sampler = new RateSampler(clock);

    private static Snapshot snap(long generation, long frames, long cmdIn, long cmdOut, long imgIn, long imgOut,
            long rtt) {
        return new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, generation, 0, "", null, 0, 0, null, 0, 0, null,
                frames, false, new TrafficStats(cmdIn, cmdOut, imgIn, imgOut, rtt));
    }

    @Test
    void firstSampleIsZero() {
        assertEquals(Rates.ZERO, sampler.sample(snap(1, 500, 0, 0, 0, 0, TrafficStats.NO_RTT)));
    }

    @Test
    void fpsFromFrameCountOverElapsed() {
        sampler.sample(snap(1, 0, 0, 0, 0, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofSeconds(2));
        assertEquals(12.0, sampler.sample(snap(1, 24, 0, 0, 0, 0, TrafficStats.NO_RTT)).fps(), 1e-9);
    }

    @Test
    void rateUsesRealElapsedNotNominalSecond() {
        sampler.sample(snap(1, 0, 0, 0, 0, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofMillis(1500));
        assertEquals(12.0, sampler.sample(snap(1, 18, 0, 0, 0, 0, TrafficStats.NO_RTT)).fps(), 1e-9);
    }

    @Test
    void kibPerSecondPerChannel() {
        sampler.sample(snap(1, 0, 0, 0, 0, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofSeconds(2));
        Rates r = sampler.sample(snap(1, 0, 1024, 1024, 100_000, 2_400, TrafficStats.NO_RTT));
        assertEquals(50.0, r.imgKiBps(), 1e-9); // 102400 bytes over 2 s
        assertEquals(1.0, r.cmdKiBps(), 1e-9); // 2048 bytes over 2 s
    }

    @Test
    void generationChangeResetsBaseline() {
        sampler.sample(snap(1, 0, 0, 0, 0, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofSeconds(1));
        sampler.sample(snap(1, 1000, 5000, 5000, 900_000, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofSeconds(1));
        // a new session: its counters start again from a small number
        Rates first = sampler.sample(snap(2, 3, 10, 10, 10, 10, TrafficStats.NO_RTT));
        assertEquals(Rates.ZERO, first);
        clock.advance(Duration.ofSeconds(1));
        Rates next = sampler.sample(snap(2, 15, 10, 10, 10 + 2048, 10, TrafficStats.NO_RTT));
        assertEquals(12.0, next.fps(), 1e-9);
        assertEquals(2.0, next.imgKiBps(), 1e-9);
        assertTrue(next.cmdKiBps() >= 0 && next.imgKiBps() >= 0 && next.fps() >= 0);
    }

    @Test
    void rttPassedThroughAndNoRttKept() {
        sampler.sample(snap(1, 0, 0, 0, 0, 0, TrafficStats.NO_RTT));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(40_000_000L, sampler.sample(snap(1, 1, 0, 0, 0, 0, 40_000_000L)).rttNanos());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(TrafficStats.NO_RTT, sampler.sample(snap(1, 2, 0, 0, 0, 0, TrafficStats.NO_RTT)).rttNanos());
    }

    @Test
    void nullSnapshotGivesZero() {
        assertEquals(Rates.ZERO, sampler.sample(null));
    }
}
