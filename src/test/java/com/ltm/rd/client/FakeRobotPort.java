package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Records port calls as "name:args" strings; no AWT Robot is ever created. */
final class FakeRobotPort implements RobotPort {
    private final Object lock = new Object();
    private final List<String> log = new ArrayList<>();
    private final Set<String> illegalArgs = new HashSet<>();
    private final Set<String> failures = new HashSet<>();

    /** The call named {@code call} throws IllegalArgumentException and is not logged. */
    void illegalArgOn(String call) {
        synchronized (lock) {
            illegalArgs.add(call);
        }
    }

    /** The call named {@code call} throws IllegalStateException and is not logged. */
    void failOn(String call) {
        synchronized (lock) {
            failures.add(call);
        }
    }

    List<String> log() {
        synchronized (lock) {
            return new ArrayList<>(log);
        }
    }

    /** Waits (bounded) until the log contains {@code call}. */
    boolean awaitCall(String call) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        synchronized (lock) {
            while (!log.contains(call)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            return true;
        }
    }

    private void call(String name) {
        boolean illegal;
        boolean fail;
        synchronized (lock) {
            illegal = illegalArgs.contains(name);
            fail = failures.contains(name);
            if (!illegal && !fail) {
                log.add(name);
                lock.notifyAll();
            }
        }
        if (illegal) {
            throw new IllegalArgumentException("unsupported " + name);
        }
        if (fail) {
            throw new IllegalStateException("injected failure " + name);
        }
    }

    @Override
    public void mouseMove(int x, int y) {
        call("move:" + x + "," + y);
    }

    @Override
    public void mousePress(int buttonMask) {
        call("press:" + buttonMask);
    }

    @Override
    public void mouseRelease(int buttonMask) {
        call("release:" + buttonMask);
    }

    @Override
    public void mouseWheel(int notches) {
        call("wheel:" + notches);
    }

    @Override
    public void keyPress(int virtualKey) {
        call("keyPress:" + virtualKey);
    }

    @Override
    public void keyRelease(int virtualKey) {
        call("keyRelease:" + virtualKey);
    }

    @Override
    public BufferedImage createScreenCapture(Rectangle bounds) {
        call("capture:" + bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height);
        return new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height),
                BufferedImage.TYPE_INT_RGB);
    }
}
