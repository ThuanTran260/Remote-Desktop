package com.ltm.rd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Records calls as "name:args" strings; supports blocking and failure injection per call. */
public final class FakeInputDevice implements InputDevice {
    public static final class Gate {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);

        public boolean awaitEntered() throws InterruptedException {
            return entered.await(2, TimeUnit.SECONDS);
        }

        public void open() {
            release.countDown();
        }
    }

    private final Object logLock = new Object();
    private final List<String> log = new ArrayList<>();
    private final Map<String, Gate> gates = new HashMap<>();
    private final Set<String> failures = new HashSet<>();
    private final Set<String> illegalArgs = new HashSet<>();

    /** The next call named {@code call} blocks until the returned gate is opened. */
    public Gate blockOn(String call) {
        Gate g = new Gate();
        synchronized (logLock) {
            gates.put(call, g);
        }
        return g;
    }

    public void failOn(String call) {
        synchronized (logLock) {
            failures.add(call);
        }
    }

    public void illegalArgOn(String call) {
        synchronized (logLock) {
            illegalArgs.add(call);
        }
    }

    public List<String> log() {
        synchronized (logLock) {
            return new ArrayList<>(log);
        }
    }

    /** Waits (bounded) until the log contains {@code call}. */
    public boolean awaitCall(String call) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        synchronized (logLock) {
            while (!log.contains(call)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(logLock, left);
            }
            return true;
        }
    }

    private void call(String name) {
        Gate gate;
        boolean fail;
        boolean illegal;
        synchronized (logLock) {
            log.add(name);
            logLock.notifyAll();
            gate = gates.remove(name);
            fail = failures.contains(name);
            illegal = illegalArgs.contains(name);
        }
        if (gate != null) {
            gate.entered.countDown();
            try {
                gate.release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    public void mouseMove(int absX, int absY) {
        call("mouseMove:" + absX + "," + absY);
    }

    @Override
    public void mousePress(int button) {
        call("mousePress:" + button);
    }

    @Override
    public void mouseRelease(int button) {
        call("mouseRelease:" + button);
    }

    @Override
    public void mouseWheel(int notches) {
        call("mouseWheel:" + notches);
    }

    @Override
    public void keyPress(int keyCode) {
        call("keyPress:" + keyCode);
    }

    @Override
    public void keyRelease(int keyCode) {
        call("keyRelease:" + keyCode);
    }
}
