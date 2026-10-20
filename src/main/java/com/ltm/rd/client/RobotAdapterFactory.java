package com.ltm.rd.client;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Production {@link AdapterFactory}. The screen source and the input device each own a separate
 * Robot, so the capture thread and the input worker never share one.
 */
public final class RobotAdapterFactory implements AdapterFactory {
    private final RobotPortFactory ports;
    private final Supplier<DisplayPort> display;
    private final BooleanSupplier headless;

    private final Object lock = new Object();
    // Guarded by lock.
    private RobotInputDevice device;

    public RobotAdapterFactory() {
        this(RobotAdapterFactory::createAwtPort, AwtDisplayPort::new, GraphicsEnvironment::isHeadless);
    }

    RobotAdapterFactory(RobotPortFactory ports, Supplier<DisplayPort> display, BooleanSupplier headless) {
        this.ports = Objects.requireNonNull(ports);
        this.display = Objects.requireNonNull(display);
        this.headless = Objects.requireNonNull(headless);
    }

    private static RobotPort createAwtPort() throws AdapterException {
        try {
            return new AwtRobotPort();
        } catch (AWTException | RuntimeException e) {
            throw new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, e);
        }
    }

    private void requireGraphics() throws AdapterException {
        if (headless.getAsBoolean()) {
            throw new AdapterException(AdapterException.Kind.HEADLESS, null);
        }
    }

    @Override
    public ScreenSource newScreenSource() throws AdapterException {
        requireGraphics();
        RobotPort port = ports.create();
        DisplayPort displayPort;
        try {
            displayPort = display.get();
        } catch (RuntimeException e) {
            throw new AdapterException(AdapterException.Kind.NO_SCREEN, e);
        }
        return new RobotScreenSource(port, displayPort);
    }

    @Override
    public InputDevice inputDevice() throws AdapterException {
        requireGraphics();
        synchronized (lock) {
            if (device == null) {
                device = new RobotInputDevice(ports);
            }
            return device;
        }
    }

    @Override
    public void refreshInputDevice() throws AdapterException {
        requireGraphics();
        RobotInputDevice current;
        synchronized (lock) {
            current = device;
        }
        if (current != null) {
            current.refresh();
        }
    }
}
