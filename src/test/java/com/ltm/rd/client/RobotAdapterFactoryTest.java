package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RobotAdapterFactoryTest {
    private static final DisplayPort DISPLAY = new DisplayPort() {
        @Override
        public Rectangle bounds() {
            return new Rectangle(0, 0, 800, 600);
        }

        @Override
        public double scaleX() {
            return 1;
        }

        @Override
        public double scaleY() {
            return 1;
        }
    };

    private final AtomicInteger created = new AtomicInteger();
    private final List<FakeRobotPort> ports = new ArrayList<>();

    private RobotPortFactory countingPorts() {
        return () -> {
            created.incrementAndGet();
            FakeRobotPort p = new FakeRobotPort();
            ports.add(p);
            return p;
        };
    }

    @Test
    void headlessReportsHeadlessAndCreatesNoRobot() {
        RobotAdapterFactory f = new RobotAdapterFactory(countingPorts(), () -> DISPLAY, () -> true);
        assertEquals(AdapterException.Kind.HEADLESS,
                assertThrows(AdapterException.class, f::newScreenSource).kind());
        assertEquals(AdapterException.Kind.HEADLESS,
                assertThrows(AdapterException.class, f::inputDevice).kind());
        assertEquals(AdapterException.Kind.HEADLESS,
                assertThrows(AdapterException.class, f::refreshInputDevice).kind());
        assertEquals(0, created.get());
    }

    @Test
    void robotCreationFailureReportsRobotUnavailable() {
        RobotAdapterFactory f = new RobotAdapterFactory(
                () -> {
                    throw new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, new RuntimeException("x"));
                }, () -> DISPLAY, () -> false);
        assertEquals(AdapterException.Kind.ROBOT_UNAVAILABLE,
                assertThrows(AdapterException.class, f::inputDevice).kind());
        assertEquals(AdapterException.Kind.ROBOT_UNAVAILABLE,
                assertThrows(AdapterException.class, f::newScreenSource).kind());
    }

    @Test
    void displayFailureReportsNoScreen() {
        RobotAdapterFactory f = new RobotAdapterFactory(countingPorts(), () -> {
            throw new IllegalStateException("no display");
        }, () -> false);
        assertEquals(AdapterException.Kind.NO_SCREEN,
                assertThrows(AdapterException.class, f::newScreenSource).kind());
    }

    @Test
    void inputDeviceCreatedOncePerFactory() throws Exception {
        RobotAdapterFactory f = new RobotAdapterFactory(countingPorts(), () -> DISPLAY, () -> false);
        InputDevice first = f.inputDevice();
        assertSame(first, f.inputDevice());
        assertEquals(1, created.get());
    }

    @Test
    void refreshInputDeviceReplacesThePortBehindTheSameDevice() throws Exception {
        RobotAdapterFactory f = new RobotAdapterFactory(countingPorts(), () -> DISPLAY, () -> false);
        InputDevice device = f.inputDevice();
        f.refreshInputDevice();
        assertSame(device, f.inputDevice());
        device.mouseMove(1, 2);
        assertEquals(2, created.get());
        assertEquals(List.of(), ports.get(0).log());
        assertEquals(List.of("move:1,2"), ports.get(1).log());
    }

    @Test
    void newScreenSourceCreatesFreshPortEachTime() throws Exception {
        RobotAdapterFactory f = new RobotAdapterFactory(countingPorts(), () -> DISPLAY, () -> false);
        ScreenSource a = f.newScreenSource();
        ScreenSource b = f.newScreenSource();
        assertNotSame(a, b);
        assertEquals(2, created.get());
    }
}
