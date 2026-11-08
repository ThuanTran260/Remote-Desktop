package com.ltm.rd.viewer;

import com.ltm.rd.viewer.ViewerPhase;
import java.awt.image.BufferedImage;

/**
 * What the UI needs about the controlled screen, read in one transaction: {@code controlEnabled}
 * implies that {@code frame} belongs to {@code inputEpoch}. The frame is never modified after it
 * was published, so the reference may be shared.
 */
public record ControlView(long generation, ViewerPhase phase, boolean controlEnabled, long inputEpoch,
        BufferedImage frame, long framesReceived) {}
