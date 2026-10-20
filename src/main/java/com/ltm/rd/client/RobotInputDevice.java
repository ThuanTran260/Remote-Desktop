package com.ltm.rd.client;

import java.awt.event.InputEvent;
import java.util.Objects;

/**
 * {@link InputDevice} on a {@link RobotPort}. Coordinates are already absolute (InputExecutor adds
 * the bounds origin), so they are forwarded unchanged. Only the {@code rd-input} worker calls the
 * device; {@link #refresh()} is called while the executor is idle.
 */
public final class RobotInputDevice implements InputDevice {
    private final RobotPortFactory factory;
    private volatile RobotPort port;

    public RobotInputDevice(RobotPortFactory factory) throws AdapterException {
        this.factory = Objects.requireNonNull(factory);
        this.port = factory.create();
    }

    /** Replaces the port with a new one; on failure the old port stays in use. */
    public void refresh() throws AdapterException {
        port = factory.create();
    }

    @Override
    public void mouseMove(int absX, int absY) {
        port.mouseMove(absX, absY);
    }

    @Override
    public void mousePress(int button) {
        port.mousePress(InputEvent.getMaskForButton(button));
    }

    @Override
    public void mouseRelease(int button) {
        port.mouseRelease(InputEvent.getMaskForButton(button));
    }

    @Override
    public void mouseWheel(int notches) {
        port.mouseWheel(notches);
    }

    @Override
    public void keyPress(int keyCode) {
        port.keyPress(keyCode);
    }

    @Override
    public void keyRelease(int keyCode) {
        port.keyRelease(keyCode);
    }
}
