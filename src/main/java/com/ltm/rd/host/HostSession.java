package com.ltm.rd.host;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Heartbeat;
import com.ltm.rd.common.LifecyclePort;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketRules.Lane;
import com.ltm.rd.common.PacketRules.Shell;
import com.ltm.rd.common.PacketRules.Stage;
import com.ltm.rd.common.PacketRules;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.ProtocolException;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.SessionPort;
import com.ltm.rd.common.SessionSide;
import com.ltm.rd.common.Tickable;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.common.WriteWatchdog;
import com.ltm.rd.host.AdapterFactory;
import com.ltm.rd.host.ControlledSide;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.host.ScreenGeometry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Server side of one session. All state changes go through {@code lifecycle.atomically}; side
 * effects (starting workers, registering tickers) are deferred with {@code tx.after}. Tokens are
 * never logged.
 */
public final class HostSession implements Tickable {
    enum AttachResult { ATTACHED, REJECTED }

    /** Collaborators a session needs besides its identity; {@code ticker} may be null in unit tests. */
    record Env(Timeouts timeouts, LongSupplier clock, Ticker ticker, BooleanSupplier shuttingDown,
            Runnable afterTokenCheck, UnaryOperator<CompletableFuture<Void>> inputReleaseSeam,
            InputExecutor input, AdapterFactory adapters, Consumer<ScreenGeometry> publishBounds,
            Runnable beforeSenderStart) {
        Env {
            Objects.requireNonNull(timeouts);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(shuttingDown);
            Objects.requireNonNull(input);
            Objects.requireNonNull(adapters);
            Objects.requireNonNull(publishBounds);
            Objects.requireNonNull(beforeSenderStart);
        }
    }

    private final long generation;
    private final byte[] token;
    private final InetAddress peer;
    private final long reservedAtNanos;
    private final SessionLifecycle<HostPhase> lifecycle;
    private final Env env;

    // guarded by the lifecycle lock (read and written only inside atomically)
    private Channel cmdChannel;
    private CommandWriter writer;
    private Channel imageChannel;

    private final SessionPort port;
    private final ControlledSide side;
    private boolean activationAttempted;
    private final CompletableFuture<Void> inputReleased;
    private volatile Heartbeat heartbeat;

    HostSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos,
            SessionLifecycle<HostPhase> lifecycle, Env env) {
        this.generation = generation;
        this.token = Objects.requireNonNull(token).clone();
        this.peer = Objects.requireNonNull(peer);
        this.reservedAtNanos = reservedAtNanos;
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.env = Objects.requireNonNull(env);
        this.inputReleased = new CompletableFuture<>();
        this.port = new LifecyclePort<>(lifecycle, generation, env.ticker(), env.clock(),
                new LifecyclePort.Shell<>() {
                    @Override public boolean isActive(HostPhase phase) { return phase == HostPhase.ACTIVE; }
                    @Override public boolean isActivationPending(HostPhase phase) { return phase == HostPhase.RESERVED; }
                    @Override public boolean activationExpired(long now) { return reserveExpired(now); }
                    @Override public CommandWriter writer() { return writer; }
                    @Override public void tryActivate(SessionLifecycle.Tx<HostPhase> tx) { maybeActivate(tx); }
                });
        this.side = new ControlledSide(port, env.input(), env.adapters(), env.publishBounds(),
                env.timeouts(), env.clock(), env.beforeSenderStart());
        lifecycle.addCloseHook(() -> SessionSide.bridgeClosing(() -> {
            CompletableFuture<Void> source = side.onClosing();
            return env.inputReleaseSeam() == null ? source : env.inputReleaseSeam().apply(source);
        }, inputReleased));
    }

    public long generation() {
        return generation;
    }

    /** Copy of the image-channel token; callers cannot alter the session's own copy. */
    public byte[] token() {
        return token.clone();
    }

    SessionLifecycle<HostPhase> lifecycle() {
        return lifecycle;
    }

    public InetAddress peer() {
        return peer;
    }

    public long reservedAtNanos() {
        return reservedAtNanos;
    }

    public HostPhase phase() {
        return lifecycle.phase();
    }

    /** The prepared Host screen size once SCREEN_INFO was accepted. */
    public Optional<Packet.SizePayload> screen() {
        return side.screen();
    }

    // ---- reserve deadline ------------------------------------------------------------------

    /** Closes the session if it is still RESERVED when the reserve window has passed. */
    @Override
    public void tick(long nowNanos) {
        if (reserveExpired(nowNanos)) {
            lifecycle.closeIf(HostPhase.RESERVED, new CloseInfo(Packet.DisconnectReason.TIMEOUT, "attach timeout", true));
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
            port.atomically(stx -> { side.onAuthenticated(stx); return null; });
            maybeActivate(tx);
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
                Optional<Packet> p = PacketIO.read(ch.in(), Protocol.CMD_MAX_LEN,
                        PacketRules.readableAfterHandshake(Shell.LISTENER, Lane.COMMAND));
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
            Stage stage = tx.phase() == HostPhase.RESERVED ? Stage.PENDING : Stage.ACTIVE;
            if (!PacketRules.allows(Shell.LISTENER, Lane.COMMAND, stage, p.type())) {
                tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected " + p.type(), true));
                return false;
            }
            switch (p.type()) {
                case PING -> port.atomically(stx -> { Heartbeat.answerPing(stx, port, p); return null; });
                case DISCONNECT -> tx.close(new CloseInfo(p.disconnectPayload().reason(), "peer disconnected", false));
                case PONG -> { }
                default -> port.atomically(stx -> { side.onPacket(stx, p); return null; });
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
            if (tx.phase() != HostPhase.RESERVED) return AttachResult.REJECTED;
            if (reserveExpired(env.clock().getAsLong())) {
                tx.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "attach timeout", true));
                return AttachResult.REJECTED;
            }
            if (env.shuttingDown().getAsBoolean()) return AttachResult.REJECTED; // volatile read, no lock
            if (!MessageDigest.isEqual(p.token(), token)) return AttachResult.REJECTED;
            if (env.afterTokenCheck() != null) env.afterTokenCheck().run(); // test seam, a no-op in production
            if (imageChannel != null) return AttachResult.REJECTED;
            if (!pc.handOver(now, env.timeouts().handshake(), tx)) return AttachResult.REJECTED;
            imageChannel = ch;
            tx.after(() -> setReadTimeout(ch, env.timeouts().imgSoTimeout()));
            lifecycle.addStopHook(ch::closeWrappersQuietly);
            maybeActivate(tx);
            return AttachResult.ATTACHED;
        });
    }

    // ---- activation ------------------------------------------------------------------------

    /**
     * Inside a transaction: once SCREEN_INFO, the image channel and the command writer are all
     * there and the reserve window is still open, queue SESSION_ACTIVE and advance only if the
     * writer accepted it. The window is judged on the clock read here, under the lock, never on a
     * timestamp the caller took before it waited for the lock.
     */
    private void maybeActivate(SessionLifecycle.Tx<HostPhase> tx) {
        if (tx.isClosed() || tx.phase() != HostPhase.RESERVED || activationAttempted) return;
        long activationNanos = env.clock().getAsLong();
        if (reserveExpired(activationNanos)) {
            tx.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "attach timeout", true));
            return;
        }
        if (writer == null || !side.readyForActive() || imageChannel == null) return;
        activationAttempted = true;
        if (!port.atomically(stx -> side.onActive(stx, activationNanos, imageChannel))) {
            tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "input session refused", true));
            return;
        }
        switch (writer.offer(Packet.sessionActive())) {
            case ACCEPTED -> tx.advance(HostPhase.RESERVED, HostPhase.ACTIVE);
            case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
            default -> tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "activation rejected", true));
        }
    }

    public long framesSent() { return side.framesSent(); }
    public long releaseAllReceived() { return side.releaseAllReceived(); }
    public int inputBeginCount() { return side.inputBeginCount(); }
    public int senderRuns() { return side.senderRuns(); }
    public Optional<Throwable> preparationFailure() { return side.preparationFailure(); }
    SessionPort port() { return port; }

    // ---- close, observability for the UI ------------------------------------------------------

    /** The user ends the session: DISCONNECT(USER) is sent to the Client. */
    public void disconnect() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "disconnected", true));
    }

    /** Closes with the caller's cause, returning whether it won the first-close decision. */
    public boolean close(CloseInfo info) {
        return lifecycle.close(info);
    }

    /** Completes when every socket of the session is closed. */
    public CompletableFuture<CloseInfo> socketsClosed() {
        return lifecycle.socketsClosed();
    }

    /** Completes when the session is closed, its sockets are closed and its workers have stopped. */
    public CompletableFuture<CloseInfo> terminated() {
        return lifecycle.terminated();
    }

    /** Stable target that completes only when the role's closing stage succeeds. */
    public CompletableFuture<Void> inputReleased() {
        return inputReleased;
    }

    public Optional<CloseInfo> closeInfo() {
        return lifecycle.closeInfo();
    }

    /** Round-trip time of the last answered ping; empty before the first answer. */
    public OptionalLong rttNanos() {
        Heartbeat hb = heartbeat;
        return hb == null ? OptionalLong.empty() : hb.lastRttNanos();
    }

    /** Byte totals of the two channels; zero for a channel that is not attached yet. */
    public TrafficSnapshot traffic() {
        return lifecycle.atomically(tx -> new TrafficSnapshot(
                cmdChannel == null ? 0 : cmdChannel.traffic().bytesIn(),
                cmdChannel == null ? 0 : cmdChannel.traffic().bytesOut(),
                imageChannel == null ? 0 : imageChannel.traffic().bytesIn(),
                imageChannel == null ? 0 : imageChannel.traffic().bytesOut()));
    }

}
