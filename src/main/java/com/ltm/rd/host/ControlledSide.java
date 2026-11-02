package com.ltm.rd.host;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** The controlled role: announced geometry, input execution and screen sending. */
public final class ControlledSide implements SessionSide {
    private static final int FPS = 12;
    private static final float FRAME_QUALITY = 0.6f;

    private final SessionPort port;
    private final InputExecutor input;
    private final AdapterFactory adapters;
    private final Consumer<ScreenGeometry> publishBounds;
    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final Runnable beforeSenderStart;
    private final AtomicInteger inputBegins = new AtomicInteger();
    private final AtomicInteger senderRuns = new AtomicInteger();

    // Prepared source and geometry belong only to this session, guarded by its transaction.
    private ScreenSource screen;
    private ScreenGeometry announced;
    private Throwable preparationFailure;
    private volatile ScreenSender sender;
    // Guarded by the port's lifecycle transaction.
    private CommandReceiver receiver;
    private boolean inputBegun;
    private boolean prepared;

    public ControlledSide(SessionPort port, InputExecutor input, AdapterFactory adapters,
            Consumer<ScreenGeometry> publishBounds, Timeouts timeouts, LongSupplier clock, Runnable beforeSenderStart) {
        this.port = Objects.requireNonNull(port);
        this.input = Objects.requireNonNull(input);
        this.adapters = Objects.requireNonNull(adapters);
        this.publishBounds = Objects.requireNonNull(publishBounds);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.clock = Objects.requireNonNull(clock);
        this.beforeSenderStart = Objects.requireNonNull(beforeSenderStart);
    }

    @Override public void onAuthenticated(SideTx stx) {
        stx.after(() -> port.startWorker("rd-host-prepare", this::prepare));
    }

    private void prepare() {
        ScreenSource source;
        try {
            adapters.refreshInputDevice();
            source = adapters.newScreenSource();
        } catch (AdapterException | RuntimeException failure) {
            port.atomically(stx -> {
                if (!canPrepare(stx)) return null;
                preparationFailure = failure;
                stx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "host screen unavailable", true));
                return null;
            });
            return;
        }
        ScreenGeometry geometry = source.geometry();
        Rectangle bounds = geometry.bounds();
        port.atomically(stx -> {
            if (!canPrepare(stx) || prepared) return null;
            CommandWriter.OfferResult result = port.offer(Packet.screenInfo(bounds.width, bounds.height));
            if (result == CommandWriter.OfferResult.ACCEPTED) {
                screen = source;
                announced = geometry;
                receiver = new CommandReceiver(input, port.generation(), bounds);
                prepared = true;
                port.tryActivate(stx);
            } else {
                stx.close(new CloseInfo(result == CommandWriter.OfferResult.OVERFLOW
                        ? Packet.DisconnectReason.OVERFLOW : Packet.DisconnectReason.PROTOCOL_ERROR,
                        "screen info rejected", true));
            }
            return null;
        });
    }

    private boolean canPrepare(SideTx stx) {
        if (stx.isClosed() || !stx.isActivationPending()) return false;
        if (stx.activationExpired()) {
            stx.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "attach timeout", true));
            return false;
        }
        return true;
    }

    @Override public boolean readyForActive() {
        return port.atomically(stx -> prepared);
    }

    @Override public void onPacket(SideTx stx, Packet p) {
        if (receiver == null) {
            stx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "input before session active", true));
        } else {
            receiver.handle(stx, p);
        }
    }

    @Override public boolean onActive(SideTx stx, long nowNanos, Channel img) {
        if (!input.beginSession(port.generation())) return false;
        inputBegun = true;
        inputBegins.incrementAndGet();
        publishBounds.accept(announced);
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

    public Optional<Packet.SizePayload> screen() {
        return port.atomically(stx -> announced == null ? Optional.empty()
                : Optional.of(new Packet.SizePayload(announced.bounds().width, announced.bounds().height)));
    }

    public Optional<Throwable> preparationFailure() {
        return port.atomically(stx -> Optional.ofNullable(preparationFailure));
    }

    public long framesSent() {
        ScreenSender current = sender;
        return current == null ? 0 : current.framesSent();
    }

    public long releaseAllReceived() {
        CommandReceiver current = port.atomically(stx -> receiver);
        return current == null ? 0 : current.releaseAllReceived();
    }
}
