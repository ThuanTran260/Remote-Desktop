package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Screen source producing blank images, no AWT Robot involved. Tests can change the reported
 * geometry, the size of the captured image, block captures, and run a hook at the next capture
 * (to know which frame is about to be sent).
 */
public final class FakeScreenSource implements ScreenSource {
    private volatile ScreenGeometry geometry;
    private volatile int captureWidth;
    private volatile int captureHeight;
    private volatile CountDownLatch gate;
    private volatile CountDownLatch blockedSignal = new CountDownLatch(1);
    private final AtomicReference<Runnable> nextCaptureHook = new AtomicReference<>();
    private final AtomicInteger captures = new AtomicInteger();
    private volatile boolean failing;

    public FakeScreenSource(int width, int height) {
        this.geometry = new ScreenGeometry(new Rectangle(0, 0, width, height), 1.0, 1.0);
    }

    @Override
    public ScreenGeometry geometry() {
        return geometry;
    }

    /** Changes the reported screen geometry, as a resolution change would. */
    public void setGeometry(int width, int height) {
        geometry = new ScreenGeometry(new Rectangle(0, 0, width, height), 1.0, 1.0);
    }

    /** Captured images get this size regardless of the requested bounds (0 = follow the bounds). */
    public void setCaptureSize(int width, int height) {
        captureWidth = width;
        captureHeight = height;
    }

    /** Blocks every capture until the returned latch is counted down. */
    public CountDownLatch blockCaptures() {
        CountDownLatch latch = new CountDownLatch(1);
        blockedSignal = new CountDownLatch(1);
        gate = latch;
        return latch;
    }

    /** Waits until a capture is parked on the gate set by the last {@link #blockCaptures()}. */
    public boolean awaitBlocked(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return blockedSignal.await(timeout, unit);
    }

    /** Runs {@code hook} once, at the start of the next capture. */
    public void onNextCapture(Runnable hook) {
        nextCaptureHook.set(hook);
    }

    /** Makes every capture throw IOException, like a lost display. */
    public void failCaptures(boolean fail) {
        failing = fail;
    }

    public int captureCount() {
        return captures.get();
    }

    @Override
    public BufferedImage capture(Rectangle bounds) throws java.io.IOException {
        Runnable hook = nextCaptureHook.getAndSet(null);
        if (hook != null) hook.run();
        captures.incrementAndGet();
        if (failing) throw new java.io.IOException("capture failed");
        CountDownLatch g = gate;
        if (g != null) {
            blockedSignal.countDown();
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("capture interrupted");
            }
        }
        int w = captureWidth > 0 ? captureWidth : bounds.width;
        int h = captureHeight > 0 ? captureHeight : bounds.height;
        return new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
    }
}
