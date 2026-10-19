package com.ltm.rd.server;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Heartbeat;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.ProtocolException;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Tickable;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.common.WriteWatchdog;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Server side of one session. All state changes go through {@code lifecycle.atomically}; side
 * effects (starting workers, registering tickers) are deferred with {@code tx.after}. Tokens are
 * never logged.
 */
public final class ServerSession implements Tickable {
    enum AttachResult { ATTACHED, REJECTED }

    /** Collaborators a session needs besides its identity; {@code ticker} may be null in unit tests. */
    record Env(Timeouts timeouts, LongSupplier clock, Ticker ticker, BooleanSupplier shuttingDown,
            Runnable afterTokenCheck, boolean receiveFrames) {
        Env(Timeouts timeouts, LongSupplier clock, Ticker ticker, BooleanSupplier shuttingDown,
                Runnable afterTokenCheck) {
            this(timeouts, clock, ticker, shuttingDown, afterTokenCheck, true);
        }

        Env {
            Objects.requireNonNull(timeouts);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(shuttingDown);
        }

        static Env standalone() {
            return new Env(Timeouts.DEFAULTS, System::nanoTime, null, () -> false, null);
        }
    }

    /** What the Client may send to the Server on the command channel. */
    private static final Set<PacketType> COMMAND_IN = EnumSet.of(PacketType.SCREEN_INFO, PacketType.PING,
            PacketType.PONG, PacketType.DISCONNECT);

    private final long generation;
    private final byte[] token;
    private final InetAddress peer;
    private final long reservedAtNanos;
    private final SessionLifecycle<ServerPhase> lifecycle;
    private final Env env;

    // guarded by the lifecycle lock (read and written only inside atomically)
    private Channel cmdChannel;
    private CommandWriter writer;
    private Packet.SizePayload screen;
    private Channel imageChannel;

    private FrameMonitor monitor; // set when the session turns ACTIVE
    private BufferedImage latestFrame;
    private long framesReceived;
    private long releaseAllSent;

    private volatile boolean framesExpected; // cheap pre-check for the ticker; true once ACTIVE
    private volatile Heartbeat heartbeat;
    private final CopyOnWriteArrayList<ObserverSlot> observers = new CopyOnWriteArrayList<>();

    ServerSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos,
            SessionLifecycle<ServerPhase> lifecycle) {
        this(generation, token, peer, reservedAtNanos, lifecycle, Env.standalone());
    }

    ServerSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos,
            SessionLifecycle<ServerPhase> lifecycle, Env env) {
        this.generation = generation;
        this.token = Objects.requireNonNull(token).clone();
        this.peer = Objects.requireNonNull(peer);
        this.reservedAtNanos = reservedAtNanos;
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.env = Objects.requireNonNull(env);
    }

    public long generation() {
        return generation;
    }

    /** Copy of the image-channel token; callers cannot alter the session's own copy. */
    public byte[] token() {
        return token.clone();
    }

    SessionLifecycle<ServerPhase> lifecycle() {
        return lifecycle;
    }

    public InetAddress peer() {
        return peer;
    }

    public long reservedAtNanos() {
        return reservedAtNanos;
    }

    public ServerPhase phase() {
        return lifecycle.phase();
    }

    /** The Client screen size once SCREEN_INFO arrived. */
    public Optional<Packet.SizePayload> screen() {
        return lifecycle.atomically(tx -> Optional.ofNullable(screen));
    }

    // ---- reserve deadline ------------------------------------------------------------------

    /** Closes the session if it is still RESERVED when the reserve window has passed. */
    @Override
    public void tick(long nowNanos) {
        if (framesExpected) {
            // the monitor decision and its consequence (gate, RELEASE_ALL, timeout) are one transaction
            lifecycle.atomically(tx -> {
                if (!tx.isClosed() && tx.phase() == ServerPhase.ACTIVE && monitor != null) {
                    handleTransition(tx, monitor.onTick(nowNanos));
                }
                return null;
            });
        } else if (reserveExpired(nowNanos)) {
            lifecycle.closeIf(ServerPhase.RESERVED, new CloseInfo(Packet.DisconnectReason.TIMEOUT, "attach timeout", true));
        }
    }

    private boolean reserveExpired(long now) {
        return now - (reservedAtNanos + env.timeouts().reserve().toNanos()) >= 0;
    }

    // ---- command channel -------------------------------------------------------------------

    /**
     * Takes over the command channel after AUTH_RESULT(OK) was sent. Everything is decided in one
     * transaction; the worker starts and ticker registrations run after the lock is released.
     */
    boolean attachCommandChannel(PendingConnection pc, Channel ch, long now) {
        return lifecycle.atomically(tx -> {
            if (cmdChannel != null) return false;
            if (!pc.handOver(now, env.timeouts().handshake(), tx)) return false;
            CommandWriter w = new CommandWriter(ch.out(), CommandWriter.DEFAULT_CAPACITY, Thread::new,
                    e -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                            "command write failed", false)),
                    env.clock());
            boolean stepSet = tx.setGracefulStep(info -> w.closeGracefully(
                    info.sendDisconnect() ? Packet.disconnect(info.reason(), info.message()) : null));
            if (!stepSet) return false; // already closed: the writer must not be started
            cmdChannel = ch;
            writer = w;

            Heartbeat hb = new Heartbeat(w, env.timeouts().pingInterval(), env.timeouts().liveness(), now,
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "liveness timeout", true)));
            WriteWatchdog dog = new WriteWatchdog(w.tracker(), env.timeouts().cmdWriteLimit(),
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "command write stuck", false)));
            heartbeat = hb;

            // closed after the sockets and after workers were interrupted (see SessionLifecycle)
            lifecycle.addStopHook(() -> {
                w.stop();
                ch.closeWrappersQuietly();
            });
            tx.after(() -> setCommandReadTimeout(ch)); // before the reader starts; not under the lock
            tx.after(() -> w.start(lifecycle::startWorker));
            tx.after(() -> lifecycle.startWorker("rd-cmd-reader", () -> readLoop(ch)));
            tx.after(() -> registerTickables(hb, dog));
            maybeActivate(tx, now);
            return true;
        });
    }

    private void setCommandReadTimeout(Channel ch) {
        setReadTimeout(ch, env.timeouts().cmdSoTimeout());
    }

    /** Replaces the handshake soTimeout once a channel belongs to the session; outside the lock. */
    private void setReadTimeout(Channel ch, java.time.Duration timeout) {
        try {
            ch.socket().setSoTimeout((int) timeout.toMillis());
        } catch (SocketException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "cannot set read timeout", false));
        }
    }

    private void registerTickables(Heartbeat hb, WriteWatchdog dog) {
        Ticker ticker = env.ticker();
        if (ticker == null) return;
        Ticker.Registration heartbeatRegistration = ticker.register(hb);
        Ticker.Registration watchdogRegistration = ticker.register(dog);
        lifecycle.addStopHook(() -> {
            heartbeatRegistration.close();
            watchdogRegistration.close();
        });
    }

    /** Body of the {@code rd-cmd-reader} worker. */
    private void readLoop(Channel ch) {
        try {
            while (true) {
                Optional<Packet> p = PacketIO.read(ch.in(), Protocol.CMD_MAX_LEN, COMMAND_IN);
                if (p.isEmpty()) {
                    lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "peer closed command channel", false));
                    return;
                }
                dispatch(p.get());
                if (lifecycle.isClosed()) return;
            }
        } catch (SocketTimeoutException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "command read timeout", true));
        } catch (ProtocolException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "protocol violation", true));
        } catch (IOException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "command channel lost", false));
        }
    }

    /** Applies one packet received on the command channel, per the phase table of spec 2.4. */
    void dispatch(Packet p) {
        long now = env.clock().getAsLong();
        boolean accepted = lifecycle.atomically(tx -> {
            if (tx.isClosed()) return false;
            ServerPhase phase = tx.phase();
            boolean allowed = switch (p.type()) {
                case SCREEN_INFO -> phase == ServerPhase.RESERVED && screen == null;
                case PING, PONG, DISCONNECT -> phase == ServerPhase.RESERVED || phase == ServerPhase.ACTIVE;
                default -> false;
            };
            if (!allowed) {
                tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected " + p.type(), true));
                return false;
            }
            switch (p.type()) {
                case SCREEN_INFO -> {
                    screen = p.sizePayload();
                    maybeActivate(tx, now);
                }
                case PING -> {
                    if (writer != null && writer.offer(Packet.pong(p.seq())) == CommandWriter.OfferResult.OVERFLOW) {
                        tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
                    }
                }
                case DISCONNECT -> tx.close(new CloseInfo(p.disconnectPayload().reason(), "peer disconnected", false));
                default -> { }
            }
            return true;
        });
        Heartbeat hb = heartbeat;
        if (accepted && hb != null) { // outside the transaction: Heartbeat has its own lock
            hb.onPacketReceived(now);
            if (p.type() == PacketType.PONG) hb.onPong(p.seq(), now);
        }
    }

    // ---- image channel ---------------------------------------------------------------------

    /**
     * Validates and takes over an image channel in one transaction, in this order: version, not
     * closed, phase RESERVED, reserve window, server not shutting down, token, no image channel
     * yet, hand-over. Only a successful hand-over marks the session attached, so any rejection
     * leaves the token usable.
     */
    AttachResult attachImage(Packet.AttachPayload p, PendingConnection pc, Channel ch, long now) {
        return lifecycle.atomically(tx -> {
            if (p.version() != Protocol.VERSION) return AttachResult.REJECTED;
            if (tx.isClosed()) return AttachResult.REJECTED;
            if (tx.phase() != ServerPhase.RESERVED) return AttachResult.REJECTED;
            if (reserveExpired(now)) return AttachResult.REJECTED;
            if (env.shuttingDown().getAsBoolean()) return AttachResult.REJECTED; // volatile read, no lock
            if (!MessageDigest.isEqual(p.token(), token)) return AttachResult.REJECTED;
            if (env.afterTokenCheck() != null) env.afterTokenCheck().run(); // test seam, a no-op in production
            if (imageChannel != null) return AttachResult.REJECTED;
            if (!pc.handOver(now, env.timeouts().handshake(), tx)) return AttachResult.REJECTED;
            imageChannel = ch;
            tx.after(() -> setReadTimeout(ch, env.timeouts().imgSoTimeout()));
            lifecycle.addStopHook(ch::closeWrappersQuietly);
            maybeActivate(tx, now);
            return AttachResult.ATTACHED;
        });
    }

    // ---- activation ------------------------------------------------------------------------

    /**
     * Inside a transaction: once SCREEN_INFO, the image channel and the command writer are all
     * there and the reserve window is still open, queue SESSION_ACTIVE and advance only if the
     * writer accepted it.
     */
    private void maybeActivate(SessionLifecycle.Tx<ServerPhase> tx, long now) {
        if (writer == null || screen == null || imageChannel == null) return;
        if (tx.isClosed() || tx.phase() != ServerPhase.RESERVED || reserveExpired(now)) return;
        switch (writer.offer(Packet.sessionActive())) {
            case ACCEPTED -> {
                if (tx.advance(ServerPhase.RESERVED, ServerPhase.ACTIVE)) onActive(tx, now);
            }
            case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
            default -> { } // CLOSED or COALESCED: the session is already going away
        }
    }

    // ---- frames and the control gate ---------------------------------------------------------

    /** Inside the activation transaction: start the frame monitor and, after unlock, the receiver. */
    private void onActive(SessionLifecycle.Tx<ServerPhase> tx, long now) {
        monitor = new FrameMonitor(now, env.timeouts().frameStall(), env.timeouts().frameTimeout());
        framesExpected = true;
        if (env.receiveFrames()) {
            FrameReceiver receiver = new FrameReceiver(this, imageChannel, new FrameDecoder(), screen.width(),
                    screen.height(), env.clock());
            tx.after(() -> lifecycle.startWorker("rd-frame-receiver", receiver));
        }
    }

    /**
     * A decoded frame from the receiver. A frame of another generation, for a closed session, or
     * after the frame timeout was decided is dropped; the monitor decision and its consequence
     * share one transaction with the tick, so exactly one of frame and timeout wins.
     */
    void publishFrame(long frameGeneration, BufferedImage image, long now) {
        lifecycle.atomically(tx -> {
            if (frameGeneration != generation || tx.isClosed() || tx.phase() != ServerPhase.ACTIVE || monitor == null) {
                return null;
            }
            FrameMonitor.Transition t = monitor.onFrame(now);
            if (t == FrameMonitor.Transition.TIMEOUT) {
                tx.closeIf(ServerPhase.ACTIVE, frameTimeout()); // dropped: the session is closing
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
    private void handleTransition(SessionLifecycle.Tx<ServerPhase> tx, FrameMonitor.Transition t) {
        if (t == FrameMonitor.Transition.NONE) return;
        long revision = monitor.revision();
        switch (t) {
            case STALL_START -> { // the gate is now closed (monitor.stalled()); release whatever the Client holds
                switch (writer.offer(Packet.releaseAll())) {
                    case ACCEPTED -> releaseAllSent++;
                    case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW,
                            "command queue overflow", true));
                    default -> { }
                }
            }
            case TIMEOUT -> tx.closeIf(ServerPhase.ACTIVE, frameTimeout());
            default -> { } // STALL_END: the gate opens by itself
        }
        tx.after(() -> notifyObservers(revision, t));
    }

    /** True once ACTIVE, a first frame arrived, no stall is in progress and the session is open. */
    public boolean controlEnabled() {
        return lifecycle.atomically(tx -> gateOpen(tx));
    }

    private boolean gateOpen(SessionLifecycle.Tx<ServerPhase> tx) {
        return !tx.isClosed() && tx.phase() == ServerPhase.ACTIVE && monitor != null && monitor.hasFrame()
                && !monitor.stalled();
    }

    /**
     * Queues an input packet for the Client. The gate check and the offer are one transaction, so
     * an input can never be queued after the stall decision that closed the gate; with the gate
     * closed the result is CLOSED.
     */
    public CommandWriter.OfferResult offerInput(Packet p) {
        switch (p.type()) {
            case MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE -> { }
            default -> throw new IllegalArgumentException("not an input packet: " + p.type());
        }
        return lifecycle.atomically(tx -> {
            if (!gateOpen(tx) || writer == null) return CommandWriter.OfferResult.CLOSED;
            CommandWriter.OfferResult result = writer.offer(p);
            if (result == CommandWriter.OfferResult.OVERFLOW) {
                tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
            }
            return result;
        });
    }

    public Optional<BufferedImage> latestFrame() {
        return lifecycle.atomically(tx -> Optional.ofNullable(latestFrame));
    }

    public long framesReceived() {
        return lifecycle.atomically(tx -> framesReceived);
    }

    /** RELEASE_ALL packets the command writer accepted because of a stall. */
    public long releaseAllSent() {
        return lifecycle.atomically(tx -> releaseAllSent);
    }

    /** For UI and tests; called outside the session lock. */
    @FunctionalInterface
    public interface FrameObserver {
        void onChange(long revision, FrameMonitor.Transition transition);
    }

    private static final class ObserverSlot {
        final FrameObserver observer;
        final AtomicLong lastRevision = new AtomicLong();

        ObserverSlot(FrameObserver observer) {
            this.observer = observer;
        }
    }

    public void addFrameObserver(FrameObserver observer) {
        observers.add(new ObserverSlot(Objects.requireNonNull(observer)));
    }

    /**
     * Delivers a transition outside the lock. Two threads may run their deliveries in the opposite
     * order of their transactions, so an observer drops anything older than what it already saw.
     */
    void notifyObservers(long revision, FrameMonitor.Transition t) {
        for (ObserverSlot slot : observers) {
            if (slot.lastRevision.getAndAccumulate(revision, Math::max) >= revision) continue;
            try {
                slot.observer.onChange(revision, t);
            } catch (RuntimeException e) {
                // an observer must not break the session
            }
        }
    }
}
