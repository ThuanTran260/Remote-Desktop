package com.ltm.rd.ui;

import com.ltm.rd.client.AdapterException;
import com.ltm.rd.client.AdapterFactory;
import com.ltm.rd.client.FakeInputDevice;
import com.ltm.rd.client.FakeScreenSource;
import com.ltm.rd.client.InputDevice;
import com.ltm.rd.client.ScreenSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Adapter factory over {@link FakeInputDevice} and {@link FakeScreenSource} that counts calls and can block or fail. */
final class FakeAdapterFactory implements AdapterFactory {
    /** Parks the next {@code newScreenSource()} until {@link #open()}. */
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean uninterruptible;

        Gate() {
            this(false);
        }

        /** An uninterruptible gate keeps the caller parked even when it is interrupted: a stuck adapter. */
        Gate(boolean uninterruptible) {
            this.uninterruptible = uninterruptible;
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }

        void open() {
            release.countDown();
        }
    }

    final FakeInputDevice device = new FakeInputDevice();
    private final AtomicInteger inputDeviceCalls = new AtomicInteger();
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicInteger newScreenCalls = new AtomicInteger();
    private final List<FakeScreenSource> screens = new CopyOnWriteArrayList<>();
    private volatile AdapterException inputDeviceFailure;
    private volatile AdapterException newScreenFailure;
    private volatile Gate newScreenGate;

    void failInputDevice(AdapterException e) {
        inputDeviceFailure = e;
    }

    void failNewScreenSource(AdapterException e) {
        newScreenFailure = e;
    }

    Gate blockNewScreenSource() {
        return blockNewScreenSource(false);
    }

    Gate blockNewScreenSource(boolean uninterruptible) {
        Gate g = new Gate(uninterruptible);
        newScreenGate = g;
        return g;
    }

    int inputDeviceCalls() {
        return inputDeviceCalls.get();
    }

    int refreshCalls() {
        return refreshCalls.get();
    }

    int newScreenCalls() {
        return newScreenCalls.get();
    }

    FakeScreenSource lastScreen() {
        return screens.get(screens.size() - 1);
    }

    @Override
    public ScreenSource newScreenSource() throws AdapterException {
        newScreenCalls.incrementAndGet();
        Gate gate = newScreenGate;
        if (gate != null) {
            newScreenGate = null;
            gate.entered.countDown();
            boolean interrupted = false;
            while (gate.release.getCount() > 0) {
                try {
                    gate.release.await();
                } catch (InterruptedException e) {
                    if (!gate.uninterruptible) {
                        Thread.currentThread().interrupt();
                        throw new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, e);
                    }
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        AdapterException failure = newScreenFailure;
        if (failure != null) throw failure;
        FakeScreenSource source = new FakeScreenSource(1920, 1080);
        screens.add(source);
        return source;
    }

    @Override
    public InputDevice inputDevice() throws AdapterException {
        inputDeviceCalls.incrementAndGet();
        AdapterException failure = inputDeviceFailure;
        if (failure != null) throw failure;
        return device;
    }

    @Override
    public void refreshInputDevice() {
        refreshCalls.incrementAndGet();
    }
}
