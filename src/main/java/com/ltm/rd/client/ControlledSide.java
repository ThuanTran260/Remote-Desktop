package com.ltm.rd.client;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionPort;
import com.ltm.rd.common.SessionSide;
import com.ltm.rd.common.SideTx;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.common.WriteWatchdog;
import java.awt.Rectangle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** The controlled role: announced geometry, input execution and screen sending. */
public final class ControlledSide implements SessionSide {
    private static final int FPS = 12;
    private static final float FRAME_QUALITY = 0.6f;

    private final SessionPort port;
    private final InputExecutor input;
    private final ScreenSource screen;
    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final Runnable beforeSenderStart;
    private final AtomicInteger inputBegins = new AtomicInteger();
    private final AtomicInteger senderRuns = new AtomicInteger();

    // Geometry is announced by the connect worker before command-channel hand-over.
    private volatile ScreenGeometry announced;
    private volatile ScreenSender sender;
    // Guarded by the port's lifecycle transaction.
    private CommandReceiver receiver;
    private boolean inputBegun;

    public ControlledSide(SessionPort port, InputExecutor input, ScreenSource screen, Timeouts timeouts,
            LongSupplier clock, Runnable beforeSenderStart) {
        this.port = Objects.requireNonNull(port);
        this.input = Objects.requireNonNull(input);
        this.screen = Objects.requireNonNull(screen);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.clock = Objects.requireNonNull(clock);
        this.beforeSenderStart = Objects.requireNonNull(beforeSenderStart);
    }

    /** v1 bridge: the connect worker reads geometry outside the transaction before hand-over. */
    public void announce(ScreenGeometry geometry) {
        announced = Objects.requireNonNull(geometry);
    }

    @Override public void onAuthenticated(SideTx stx) {
        Rectangle bounds = announced.bounds();
        receiver = new CommandReceiver(input, port.generation(), bounds);
        port.offer(Packet.screenInfo(bounds.width, bounds.height));
    }

    @Override public boolean readyForActive() {
        return announced != null;
    }

    @Override public void onPacket(SideTx stx, Packet p) {
        receiver.handle(stx, p);
    }

    @Override public boolean onActive(SideTx stx, long nowNanos, Channel img) {
        if (!input.beginSession(port.generation())) return false;
        inputBegun = true;
        inputBegins.incrementAndGet();
        stx.after(() -> startSender(img));
        return true;
    }

    private void startSender(Channel img) {
        beforeSenderStart.run();
        ScreenSender sender = new ScreenSender(port, announced, img, screen,
                new FrameEncoder(Protocol.MAX_FRAME_WIDTH, FRAME_QUALITY), FPS, clock);
        this.sender = sender;
        WriteWatchdog dog = new WriteWatchdog(sender.tracker(), timeouts.imgWriteLimit(),
                () -> port.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "image write stuck", true)));
        Ticker.Registration registration = port.register(dog);
        port.addStopHook(registration::close);
        Runnable body = sender.body();
        port.startWorker("rd-screen-sender", () -> {
            senderRuns.incrementAndGet();
            body.run();
        });
    }

    @Override public CompletableFuture<Void> onClosing() {
        boolean begun = port.atomically(stx -> inputBegun);
        return begun ? input.endSession(port.generation()) : CompletableFuture.completedFuture(null);
    }

    public int inputBeginCount() { return inputBegins.get(); }
    public int senderRuns() { return senderRuns.get(); }

    public long framesSent() {
        ScreenSender current = sender;
        return current == null ? 0 : current.framesSent();
    }

    public long releaseAllReceived() {
        CommandReceiver current = port.atomically(stx -> receiver);
        return current == null ? 0 : current.releaseAllReceived();
    }
}
