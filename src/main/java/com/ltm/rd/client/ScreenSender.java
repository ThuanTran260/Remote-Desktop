package com.ltm.rd.client;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.WriteTracker;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Body of the {@code rd-screen-sender} worker: capture, encode and send a frame every
 * {@code 1000 / fps} ms. Every write goes through a {@link WriteTracker} so a watchdog can
 * declare it stuck; once it did, nothing more is sent. The geometry is compared with the
 * announced one every second, and a change closes the session with SCREEN_CHANGED. Image
 * content is never logged.
 */
public final class ScreenSender {
    private static final long GEOMETRY_CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long SLEEP_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final ClientSession session;
    private final Channel img;
    private final ScreenSource screen;
    private final FrameEncoder encoder;
    private final long periodNanos;
    private final LongSupplier clock;
    private final WriteTracker tracker = new WriteTracker();

    public ScreenSender(ClientSession session, Channel img, ScreenSource screen, FrameEncoder encoder, int fps,
            LongSupplier nanoClock) {
        if (fps < 1) throw new IllegalArgumentException("fps must be >= 1");
        this.session = Objects.requireNonNull(session);
        this.img = Objects.requireNonNull(img);
        this.screen = Objects.requireNonNull(screen);
        this.encoder = Objects.requireNonNull(encoder);
        this.periodNanos = TimeUnit.SECONDS.toNanos(1) / fps;
        this.clock = Objects.requireNonNull(nanoClock);
    }

    /** The write state a watchdog observes; EXPIRED ends the loop. */
    public WriteTracker tracker() {
        return tracker;
    }

    /** The worker body, to run through {@code lifecycle.startWorker("rd-screen-sender", ...)}. */
    public Runnable body() {
        return this::run;
    }

    private void run() {
        SessionLifecycle<ClientPhase> lifecycle = session.lifecycle();
        ScreenGeometry initial = session.announcedGeometry();
        long nextGeometryCheck = clock.getAsLong() + GEOMETRY_CHECK_NANOS;
        try {
            while (!lifecycle.isClosed()) {
                long cycleStart = clock.getAsLong();
                if (cycleStart - nextGeometryCheck >= 0) {
                    nextGeometryCheck = cycleStart + GEOMETRY_CHECK_NANOS;
                    if (!initial.equals(screen.geometry())) {
                        lifecycle.close(new CloseInfo(Packet.DisconnectReason.SCREEN_CHANGED, "screen changed", true));
                        return;
                    }
                }
                BufferedImage image = screen.capture(initial.bounds());
                FrameEncoder.EncodedFrame frame = encoder.encode(image);
                Packet packet = Packet.frame(frame.width(), frame.height(), frame.jpeg());
                long op = tracker.begin(clock.getAsLong());
                if (op == WriteTracker.EXPIRED_OP) return; // the watchdog declared a write stuck: send nothing more
                boolean intact;
                try {
                    PacketIO.write(img.out(), packet);
                    img.out().flush();
                } finally {
                    intact = tracker.end(op);
                }
                if (!intact) return;
                sleepRest(lifecycle, cycleStart);
            }
        } catch (Throwable e) { // incl. interruption of a blocked write: the session decides what that means
            if (!lifecycle.isClosed()) {
                lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "image send failed", false));
            }
        }
    }

    /** Sleeps out the rest of the cycle in short slices, leaving early once the session is closed. */
    private void sleepRest(SessionLifecycle<ClientPhase> lifecycle, long cycleStart) {
        long deadline = cycleStart + periodNanos;
        while (!lifecycle.isClosed()) {
            long left = deadline - clock.getAsLong();
            if (left <= 0) return;
            LockSupport.parkNanos(Math.min(left, SLEEP_SLICE_NANOS));
            if (Thread.currentThread().isInterrupted()) return; // the close procedure interrupts workers
        }
    }
}
