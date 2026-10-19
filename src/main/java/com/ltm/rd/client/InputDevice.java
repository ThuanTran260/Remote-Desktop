package com.ltm.rd.client;

/**
 * Low-level input sink driven by the single {@code rd-input} worker thread.
 * Coordinates are absolute screen coordinates; {@code button} is 1..3.
 */
public interface InputDevice {
    void mouseMove(int absX, int absY);

    void mousePress(int button);

    void mouseRelease(int button);

    void mouseWheel(int notches);

    /** @throws IllegalArgumentException if the key code is not supported by the device */
    void keyPress(int keyCode);

    void keyRelease(int keyCode);
}
