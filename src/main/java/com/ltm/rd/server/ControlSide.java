package com.ltm.rd.server;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.SessionPort;
import com.ltm.rd.common.SessionSide;
import com.ltm.rd.common.SideTx;
import com.ltm.rd.common.Timeouts;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/** The controlling role: frame liveness, input gating and ordered frame notifications. */
public final class ControlSide implements SessionSide {
    private final SessionPort port;
    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final boolean receiveFrames;

    // Guarded by the port's lifecycle transaction.
    private Packet.SizePayload screen;
    private FrameMonitor monitor;
    private BufferedImage latestFrame;
    private long framesReceived;
    private long releaseAllSent;
    private long controlReleasesSent;
    private volatile long activeAtNanos;
    private volatile boolean framesExpected;
    private final CopyOnWriteArrayList<ObserverSlot> observers = new CopyOnWriteArrayList<>();

    public ControlSide(SessionPort port, Timeouts timeouts, LongSupplier clock, boolean receiveFrames) {
        this.port = Objects.requireNonNull(port);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.clock = Objects.requireNonNull(clock);
        this.receiveFrames = receiveFrames;
    }

    @Override public void onAuthenticated(SideTx stx) { }

    @Override public boolean readyForActive() {
        return port.atomically(stx -> screen != null);
    }

    @Override public void onPacket(SideTx stx, Packet p) {
        if (p.type() == PacketType.SCREEN_INFO && screen == null) {
            screen = p.sizePayload();
            port.tryActivate(stx);
        } else {
            stx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected " + p.type(), true));
        }
    }

    /** Inside activation: initialize the monitor and defer the receiver until after unlock. */
    @Override public boolean onActive(SideTx stx, long now, Channel img) {
        monitor = new FrameMonitor(now, timeouts.frameStall(), timeouts.frameTimeout());
        activeAtNanos = now;
        framesExpected = true;
        if (receiveFrames) {
            FrameReceiver receiver = new FrameReceiver(port, this, img, new FrameDecoder(), screen.width(),
                    screen.height(), clock);
            stx.after(() -> port.startWorker("rd-frame-receiver", receiver));
        }
        return true;
    }

    @Override public CompletableFuture<Void> onClosing() {
        return CompletableFuture.completedFuture(null);
    }

    public Optional<Packet.SizePayload> screen() {
        return port.atomically(stx -> Optional.ofNullable(screen));
    }

    /** False before activation, so the shell can still enforce its attachment deadline. */
    boolean tick(long now) {
        if (!framesExpected) return false;
        port.atomically(tx -> {
            if (!tx.isClosed() && tx.isActive() && monitor != null) {
                handleTransition(tx, monitor.onTick(now));
            }
            return null;
        });
        return true;
    }

    /**
     * A decoded frame from the receiver. A frame of another generation, for a closed session, or
     * after the frame timeout was decided is dropped; the monitor decision and its consequence
     * share one transaction with the tick, so exactly one of frame and timeout wins.
     */
    void publishFrame(long frameGeneration, BufferedImage image, long now) {
        port.atomically(tx -> {
            if (frameGeneration != port.generation() || tx.isClosed() || !tx.isActive() || monitor == null) {
                return null;
            }
            FrameMonitor.Transition t = monitor.onFrame(now);
            if (t == FrameMonitor.Transition.TIMEOUT) {
                tx.close(frameTimeout()); // dropped: the session is closing
                return null;
            }
            latestFrame = image;
            framesReceived++;
            handleTransition(tx, t);
            return null;
        });
    }

    private static CloseInfo frameTimeout() {
        return new CloseInfo(Packet.DisconnectReason.TIMEOUT, "frame timeout", true);
    }

    /** Inside the transaction that produced {@code t}: no input can slip in between gate and RELEASE_ALL. */
    private void handleTransition(SideTx tx, FrameMonitor.Transition t) {
        if (t == FrameMonitor.Transition.NONE) return;
        long revision = monitor.revision();
        switch (t) {
            case STALL_START -> { // the gate is now closed (monitor.stalled()); release whatever the Client holds
                switch (port.offer(Packet.releaseAll())) {
                    case ACCEPTED -> releaseAllSent++;
                    case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW,
                            "command queue overflow", true));
                    default -> { }
                }
            }
            case TIMEOUT -> tx.close(frameTimeout());
            default -> { } // STALL_END: the gate opens by itself
        }
        for (ObserverSlot slot : observers) slot.queue.add(new Notification(revision, t)); // order = transaction order
        tx.after(this::drainObservers);
    }

    /** True once ACTIVE, a first frame arrived, no stall is in progress and the session is open. */
    public boolean controlEnabled() {
        return port.atomically(tx -> gateOpen(tx));
    }

    private boolean gateOpen(SideTx tx) {
        return !tx.isClosed() && tx.isActive() && monitor != null && monitor.hasFrame()
                && !monitor.stalled();
    }

    /**
     * Queues an input packet for the Client. The gate check and the offer are one transaction, so
     * an input can never be queued after the stall decision that closed the gate; with the gate
     * closed the result is CLOSED.
     */
    public CommandWriter.OfferResult offerInput(Packet p) {
        requireInputPacket(p);
        return port.atomically(tx -> {
            if (!gateOpen(tx)) return CommandWriter.OfferResult.CLOSED;
            CommandWriter.OfferResult result = port.offer(p);
            if (result == CommandWriter.OfferResult.OVERFLOW) {
                tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
            }
            return result;
        });
    }

    /**
     * Like {@link #offerInput(Packet)} but for the frame the user saw: gate, then epoch, then the
     * offer, all in one transaction. During a stall the result is GATE_CLOSED; after the stall ended
     * an old epoch is STALE_EPOCH, so input aimed at an earlier picture is never sent.
     */
    public InputOutcome offerInput(long inputEpoch, Packet p) {
        requireInputPacket(p);
        return port.atomically(tx -> {
            if (!gateOpen(tx)) return InputOutcome.GATE_CLOSED;
            if (inputEpoch != monitor.revision()) return InputOutcome.STALE_EPOCH;
            switch (port.offer(p)) {
                case OVERFLOW -> {
                    tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
                    return InputOutcome.OVERFLOW;
                }
                case CLOSED -> {
                    return InputOutcome.GATE_CLOSED;
                }
                default -> {
                    return InputOutcome.SENT; // ACCEPTED, or a mouse move coalesced into the queued one
                }
            }
        });
    }

    private static void requireInputPacket(Packet p) {
        switch (p.type()) {
            case MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE -> { }
            default -> throw new IllegalArgumentException("not an input packet: " + p.type());
        }
    }

    /**
     * Asks the Client to release everything it holds (focus lost, user disconnect). Independent of
     * the frame gate: while frames stall this is exactly when it is needed. CLOSED when the session
     * is closed or not ACTIVE; a full queue closes the session with OVERFLOW.
     */
    public CommandWriter.OfferResult releaseControlInput() {
        return port.atomically(tx -> {
            if (tx.isClosed() || !tx.isActive()) {
                return CommandWriter.OfferResult.CLOSED;
            }
            CommandWriter.OfferResult result = port.offer(Packet.releaseAll());
            switch (result) {
                case ACCEPTED -> controlReleasesSent++;
                case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW,
                        "command queue overflow", true));
                default -> { }
            }
            return result;
        });
    }

    /** RELEASE_ALL packets the command writer accepted on request of {@link #releaseControlInput()}. */
    public long controlReleasesSent() {
        return port.atomically(tx -> controlReleasesSent);
    }

    /**
     * The input epoch: the frame monitor's revision, 0 before the session is ACTIVE. It changes on
     * every stall start, stall end and timeout, so any change means "the picture the user aimed at
     * is gone".
     */
    public long inputEpoch() {
        return port.atomically(tx -> monitor == null ? 0 : monitor.revision());
    }

    public Optional<BufferedImage> latestFrame() {
        return port.atomically(tx -> Optional.ofNullable(latestFrame));
    }

    /** The instant the session turned ACTIVE, from the session clock; empty before that. */
    public java.util.OptionalLong activeAtNanos() {
        return framesExpected ? java.util.OptionalLong.of(activeAtNanos) : java.util.OptionalLong.empty();
    }

    public long framesReceived() {
        return port.atomically(tx -> framesReceived);
    }

    /** RELEASE_ALL packets the command writer accepted because of a stall. */
    public long releaseAllSent() {
        return port.atomically(tx -> releaseAllSent);
    }

    /** For UI and tests; called outside the session lock. */
    @FunctionalInterface
    public interface FrameObserver {
        void onChange(long revision, FrameMonitor.Transition transition);
    }

   private record Notification(long revision, FrameMonitor.Transition transition) {}

    private static final class ObserverSlot {
        final FrameObserver observer;
        final java.util.Queue<Notification> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();

        ObserverSlot(FrameObserver observer) {
            this.observer = observer;
        }
    }

    /** Observers registered later only see later transitions. */
    public void addFrameObserver(FrameObserver observer) {
        observers.add(new ObserverSlot(Objects.requireNonNull(observer)));
    }

    /**
     * Delivers queued notifications outside the lock. Notifications are queued inside the
     * transaction, so their order is the transaction order; a single drainer per observer
     * delivers them one at a time, so a later transition can never overtake an earlier one even
     * when the earlier delivery is slow.
     */
    private void drainObservers() {
        for (ObserverSlot slot : observers) drain(slot);
    }

    private static void drain(ObserverSlot slot) {
        while (true) {
            if (!slot.draining.compareAndSet(false, true)) return; // the current drainer will see our entry
            try {
                Notification n;
                while ((n = slot.queue.poll()) != null) {
                    try {
                        slot.observer.onChange(n.revision(), n.transition());
                    } catch (RuntimeException e) {
                        // an observer must not break the session
                    }
                }
            } finally {
                slot.draining.set(false);
            }
            if (slot.queue.isEmpty()) return; // else an entry arrived between the last poll and the release: go again
        }
    }
}
