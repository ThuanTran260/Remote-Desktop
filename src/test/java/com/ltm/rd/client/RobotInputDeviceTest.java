package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RobotInputDeviceTest {
    private final FakeRobotPort port = new FakeRobotPort();

    private RobotInputDevice device() throws AdapterException {
        return new RobotInputDevice(() -> port);
    }

    @Test
    void mouseMoveForwardsAbsoluteCoordinatesUnchanged() throws Exception {
        device().mouseMove(1920, 1080);
        assertEquals(List.of("move:1920,1080"), port.log());
    }

    @Test
    void buttonsMapToDownMasks() throws Exception {
        RobotInputDevice d = device();
        d.mousePress(1);
        d.mousePress(2);
        d.mousePress(3);
        d.mouseRelease(1);
        d.mouseRelease(2);
        d.mouseRelease(3);
        assertEquals(List.of("press:1024", "press:2048", "press:4096",
                "release:1024", "release:2048", "release:4096"), port.log());
    }

    @Test
    void wheelForwardsSignedNotches() throws Exception {
        device().mouseWheel(-3);
        assertEquals(List.of("wheel:-3"), port.log());
    }

    @Test
    void unsupportedKeyPropagatesIllegalArgument() throws Exception {
        port.illegalArgOn("keyPress:999999");
        RobotInputDevice d = device();
        assertThrows(IllegalArgumentException.class, () -> d.keyPress(999999));
        assertTrue(port.log().isEmpty());
    }

    @Test
    void releaseFailureIsNotSwallowed() throws Exception {
        port.failOn("keyRelease:65");
        RobotInputDevice d = device();
        assertThrows(IllegalStateException.class, () -> d.keyRelease(65));
    }

    @Test
    void executorAddsBoundsOriginExactlyOnce() throws Exception {
        InputExecutor exec = new InputExecutor(device(), () -> new Rectangle(100, 50, 800, 600), 16,
                InputExecutor.defaultThreadFactory(), t -> { });
        try {
            assertTrue(exec.beginSession(1));
            assertEquals(SubmitResult.ACCEPTED, exec.submit(1, new InputCommand.MouseMove(10, 20)));
            // The sentinel wheel proves the worker has processed the move before it.
            assertEquals(SubmitResult.ACCEPTED, exec.submit(1, new InputCommand.Wheel(77)));
            assertTrue(port.awaitCall("wheel:77"));
            assertEquals(List.of("move:110,70", "wheel:77"), port.log());
        } finally {
            exec.shutdownAsync().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void refreshSwapsPortOnSuccess() throws Exception {
        FakeRobotPort port2 = new FakeRobotPort();
        Queue<RobotPort> ports = new ArrayDeque<>(List.of(port, port2));
        RobotInputDevice d = new RobotInputDevice(ports::remove);
        d.mouseMove(1, 2);
        d.refresh();
        d.mouseMove(3, 4);
        assertEquals(List.of("move:1,2"), port.log());
        assertEquals(List.of("move:3,4"), port2.log());
    }

    @Test
    void refreshFailureKeepsOldPortAndReportsRobotUnavailable() throws Exception {
        int[] calls = {0};
        RobotInputDevice d = new RobotInputDevice(() -> {
            if (calls[0]++ == 0) {
                return port;
            }
            throw new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, new RuntimeException("x"));
        });
        AdapterException e = assertThrows(AdapterException.class, d::refresh);
        assertSame(AdapterException.Kind.ROBOT_UNAVAILABLE, e.kind());
        d.mouseMove(5, 6);
        assertEquals(List.of("move:5,6"), port.log());
    }
}
