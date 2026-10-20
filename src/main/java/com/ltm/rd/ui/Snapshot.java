package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import java.awt.image.BufferedImage;

/**
 * Immutable view of the application for the UI. {@code frame} is never modified after it was
 * published, so the reference may be shared. {@code controlEnabled} and {@code frame} of the
 * Server come from one transaction: enabled implies the frame belongs to {@code inputEpoch}.
 */
public record Snapshot(LaunchOptions.Role role, UiPhase phase, long generation, long inputEpoch, String message,
        String peer, int cmdPort, int imgPort, String code, int screenWidth, int screenHeight,
        BufferedImage frame, long frameCount, boolean controlEnabled, TrafficStats stats) {

    /** Never prints the code, the peer address or the image. */
    @Override
    public String toString() {
        return "Snapshot[role=" + role + ", phase=" + phase + ", generation=" + generation
                + ", inputEpoch=" + inputEpoch + "]";
    }
}
