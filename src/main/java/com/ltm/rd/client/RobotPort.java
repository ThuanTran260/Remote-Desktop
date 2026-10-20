package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Narrow view of {@link java.awt.Robot} so adapters can be tested without creating one. */
public interface RobotPort {
    void mouseMove(int x, int y);

    /** @param buttonMask an {@code InputEvent.BUTTONn_DOWN_MASK} value */
    void mousePress(int buttonMask);

    void mouseRelease(int buttonMask);

    /** Positive scrolls down, like {@code MouseWheelEvent.getWheelRotation()}. */
    void mouseWheel(int notches);

    /** @throws IllegalArgumentException if the virtual key is not supported */
    void keyPress(int virtualKey);

    void keyRelease(int virtualKey);

    BufferedImage createScreenCapture(Rectangle bounds);
}
