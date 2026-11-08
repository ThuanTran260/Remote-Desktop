package com.ltm.rd.ui;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns the cumulative counters of successive snapshots into rates, dividing by the time that
 * really passed between the two samples (a timer tick is never exactly one second). The first
 * sample and the first sample after a new generation only set the baseline.
 */
public final class RateSampler {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final LongSupplier nanoClock;
    private boolean hasBaseline;
    private long baseGeneration;
    private long baseTime;
    private long baseFrames;
    private long baseCmd;
    private long baseImg;

    public RateSampler(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public Rates sample(Snapshot s) {
        if (s == null) return Rates.ZERO;
        long now = nanoClock.getAsLong();
        TrafficStats t = s.stats();
        long cmd = t.cmdBytesIn() + t.cmdBytesOut();
        long img = t.imgBytesIn() + t.imgBytesOut();
        if (!hasBaseline || s.generation() != baseGeneration) {
            setBaseline(s.generation(), now, s.frameCount(), cmd, img);
            return Rates.ZERO;
        }
        double seconds = (now - baseTime) / NANOS_PER_SECOND;
        if (seconds <= 0) return Rates.ZERO; // no time passed: keep the baseline
        Rates rates = new Rates(
                Math.max(0, s.frameCount() - baseFrames) / seconds,
                Math.max(0, cmd - baseCmd) / 1024.0 / seconds,
                Math.max(0, img - baseImg) / 1024.0 / seconds,
                t.rttNanos());
        setBaseline(s.generation(), now, s.frameCount(), cmd, img);
        return rates;
    }

    private void setBaseline(long generation, long now, long frames, long cmd, long img) {
        hasBaseline = true;
        baseGeneration = generation;
        baseTime = now;
        baseFrames = frames;
        baseCmd = cmd;
        baseImg = img;
    }
}
