package com.ltm.rd.viewer;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.ClosedSessionException;
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
import com.ltm.rd.common.TrafficCounter;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.common.WriteWatchdog;
import com.ltm.rd.viewer.ControlSide;
import com.ltm.rd.viewer.ControlView;
import com.ltm.rd.viewer.InputOutcome;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Viewer side of one session. Handshake order (spec 1.4): command socket, AUTH, AUTH_RESULT,
 * command channel hand-over with SCREEN_INFO, image socket, ATTACH. Frame receiving starts exactly once,
 * inside a transaction, when SESSION_ACTIVE has arrived and the image channel is ready. Codes and
 * tokens are never logged.
 */
public final class ViewerSession implements Tickable {

    private final ViewerConfig cfg;
    private final Timeouts timeouts;
    private final SocketConnector connector;
    private final ViewerHooks hooks;
    private final Ticker ticker;
    private final SessionLifecycle<ViewerPhase> lifecycle;

    private final AtomicBoolean startRequested = new AtomicBoolean();
    private final CompletableFuture<Void> activated = new CompletableFuture<>();
    private final CompletableFuture<Void> inputReleased = new CompletableFuture<>();
    private final AtomicReference<Packet.AuthStatus> authFailure = new AtomicReference<>();
    private final SessionPort port;
    private final ControlSide side;

    // guarded by the lifecycle lock (read and written only inside atomically)
    private boolean activationReceived;
    private boolean imageChannelReady;
    private boolean startAttempted;
    private CommandWriter writer;
    private Channel imageChannel;
    private Channel commandChannel;

    private volatile Heartbeat heartbeat;
    private volatile Long authDeadlineNanos; // set when AUTHENTICATING begins
    private volatile Long activationDeadlineNanos; // set when ATTACHING begins

    public ViewerSession(ViewerConfig cfg, long generation, SocketConnector connector, ViewerHooks hooks,
            Ticker ticker, ScheduledExecutorService scheduler,
            Executor cleanup) {
        this.cfg = Objects.requireNonNull(cfg);
        this.timeouts = cfg.timeouts();
        this.connector = Objects.requireNonNull(connector);
        this.hooks = Objects.requireNonNull(hooks);
        this.ticker = Objects.requireNonNull(ticker);
        this.lifecycle = new SessionLifecycle<>(ViewerPhase.CONNECTING, ViewerPhase.CLOSING, cfg.nanoClock(),
                timeouts.closeGrace(), scheduler, cleanup);
        this.port = new LifecyclePort<>(lifecycle, generation, ticker, cfg.nanoClock(),
                new LifecyclePort.Shell<>() {
                    @Override public boolean isActive(ViewerPhase phase) { return phase == ViewerPhase.ACTIVE; }
                    @Override public boolean isActivationPending(ViewerPhase phase) { return phase == ViewerPhase.ATTACHING; }
                    @Override public boolean activationExpired(long now) {
                        Long deadline = activationDeadlineNanos;
                        return deadline != null && now - deadline >= 0;
                    }
                    @Override public CommandWriter writer() { return writer; }
                    @Override public void tryActivate(SessionLifecycle.Tx<ViewerPhase> tx) { tryStart(tx); }
                });
        this.side = new ControlSide(port, timeouts, cfg.nanoClock(), true);
        lifecycle.addCloseHook(this::onClosing);
    }

    // ---- public surface --------------------------------------------------------------------

    /** Starts the {@code rd-connect} worker through the lifecycle; false if already started or closed. */
    public boolean start() {
        if (!startRequested.compareAndSet(false, true)) return false;
        Ticker.Registration registration = ticker.register(this);
        lifecycle.addStopHook(registration::close);
        return lifecycle.startWorker("rd-connect", this::connectBody);
    }

    /** Completes when the session reaches ACTIVE; fails if it closes first. */
    public CompletableFuture<Void> activated() {
        return activated;
    }

    public ViewerPhase phase() {
        return lifecycle.phase();
    }

    public void cancel() {
        lifecycle.close(new CloseInfo(Packet.DisconnectReason.USER, "cancelled", true));
    }

    public CompletableFuture<CloseInfo> socketsClosed() {
        return lifecycle.socketsClosed();
    }

    public CompletableFuture<CloseInfo> terminated() {
        return lifecycle.terminated();
    }

    public Optional<Packet.AuthStatus> authFailure() {
        return Optional.ofNullable(authFailure.get());
    }

    /**
     * Completes when the Viewer side closes. The Viewer owns no native input resources.
     */
    public CompletableFuture<Void> inputReleased() {
        return inputReleased;
    }

    public SessionLifecycle<ViewerPhase> lifecycle() {
        return lifecycle;
    }

    /** Byte totals of the two channels; zero for a channel that is not attached yet. */
    public TrafficSnapshot traffic() {
        return lifecycle.atomically(tx -> new TrafficSnapshot(
                commandChannel == null ? 0 : commandChannel.traffic().bytesIn(),
                commandChannel == null ? 0 : commandChannel.traffic().bytesOut(),
                imageChannel == null ? 0 : imageChannel.traffic().bytesIn(),
                imageChannel == null ? 0 : imageChannel.traffic().bytesOut()));
    }

    /** Round-trip time of the last answered ping; empty before the first answer. */
    public OptionalLong rttNanos() {
        Heartbeat hb = heartbeat;
        return hb == null ? OptionalLong.empty() : hb.lastRttNanos();
    }

    public long generation() { return port.generation(); }

    public Optional<Packet.SizePayload> screen() { return side.screen(); }
    public boolean controlEnabled() { return side.controlEnabled(); }
    public CommandWriter.OfferResult offerInput(Packet p) { return side.offerInput(p); }
    public InputOutcome offerInput(long inputEpoch, Packet p) { return side.offerInput(inputEpoch, p); }
    public CommandWriter.OfferResult releaseControlInput() { return side.releaseControlInput(); }
    public long controlReleasesSent() { return side.controlReleasesSent(); }
    public long inputEpoch() { return side.inputEpoch(); }

    /** Gate, epoch, latest frame and frame count read under the same lifecycle lock. */
    public ControlView controlView() {
        return lifecycle.atomically(tx -> new ControlView(generation(), tx.phase(), side.controlEnabled(),
                side.inputEpoch(), side.latestFrame().orElse(null), side.framesReceived()));
    }

    SessionPort port() { return port; }
    ControlSide controlSide() { return side; }

    public Optional<BufferedImage> latestFrame() { return side.latestFrame(); }
    public OptionalLong activeAtNanos() { return side.activeAtNanos(); }
    public long framesReceived() { return side.framesReceived(); }
    public long releaseAllSent() { return side.releaseAllSent(); }
    public void addFrameObserver(ControlSide.FrameObserver observer) { side.addFrameObserver(observer); }

    // Observability for tests: how far the two start conditions and the start itself got.

    public boolean activationReceived() {
        return lifecycle.atomically(tx -> activationReceived);
    }

    public boolean imageChannelReady() {
        return lifecycle.atomically(tx -> imageChannelReady);
    }

    // ---- deadlines -------------------------------------------------------------------------

    /** Phase deadlines: the check and the close are one decision inside {@code closeIf}. */
    @Override
    public void tick(long nowNanos) {
        side.tick(nowNanos);
        Long auth = authDeadlineNanos;
        if (auth != null && nowNanos - auth >= 0) {
            lifecycle.closeIf(ViewerPhase.AUTHENTICATING,
                    new CloseInfo(Packet.DisconnectReason.TIMEOUT, "auth timeout", true));
        }
        Long activation = activationDeadlineNanos;
        if (activation != null && nowNanos - activation >= 0) {
            lifecycle.closeIf(ViewerPhase.ATTACHING,
                    new CloseInfo(Packet.DisconnectReason.TIMEOUT, "activation timeout", true));
        }
    }

    // ---- connect worker --------------------------------------------------------------------

    /** Body of {@code rd-connect}; never throws, any failure closes the session. */
    private void connectBody() {
        try {
            handshake();
        } catch (ClosedSessionException e) {
            // closed meanwhile (cancel or deadline): nothing to add
        } catch (Throwable e) {
            if (!lifecycle.isClosed()) {
                lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, failureMessage(e), false));
            }
        }
    }

    private static String failureMessage(Throwable e) {
        return e instanceof ProtocolException ? "protocol violation" : "handshake failed";
    }

    /** Connects through the seam; a failure closes the session with a clear cause. */
    private boolean connect(Socket socket, int port) {
        try {
            connector.connect(socket, new InetSocketAddress(cfg.host(), port), (int) timeouts.connect().toMillis());
            return true;
        } catch (IOException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "connect failed", false));
            return false;
        }
    }

    private void handshake() throws IOException {
        Socket cmd = lifecycle.register(new Socket());
        cmd.setTcpNoDelay(true);
        if (!connect(cmd, cfg.cmdPort())) return;

        boolean entered = lifecycle.atomically(tx -> {
            if (!tx.advance(ViewerPhase.CONNECTING, ViewerPhase.AUTHENTICATING)) return false;
            authDeadlineNanos = cfg.nanoClock().getAsLong() + timeouts.clientAuth().toNanos();
            return true;
        });
        if (!entered) return;

        Channel cmdChannel = Channel.wrap(cmd, new TrafficCounter()); // once, before the first packet
        cmd.setSoTimeout((int) timeouts.clientAuth().toMillis()); // fallback if the deadline tick is late
        lifecycle.addStopHook(cmdChannel::closeWrappersQuietly);
        PacketIO.write(cmdChannel.out(), Packet.auth(cfg.code()));
        cmdChannel.out().flush();
        Optional<Packet> reply;
        try {
            reply = PacketIO.read(cmdChannel.in(), Protocol.CMD_MAX_LEN,
                    PacketRules.allowedIn(Shell.CONNECTOR, Lane.COMMAND, Stage.HANDSHAKE));
        } catch (SocketTimeoutException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "auth timeout", true));
            return;
        }
        if (reply.isEmpty()) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "server closed connection", false));
            return;
        }
        Packet.AuthResultPayload result = reply.get().authResultPayload();
        if (result.status() != Packet.AuthStatus.OK) {
            authFailure.set(result.status());
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                    "auth rejected: " + result.status(), false));
            return;
        }

        if (!attachCommandChannel(cmdChannel)) return;

        Socket img = lifecycle.register(new Socket());
        img.setTcpNoDelay(true);
        if (!connect(img, cfg.imgPort())) return;
        Channel imgChannel = Channel.wrap(img, new TrafficCounter());
        lifecycle.addStopHook(imgChannel::closeWrappersQuietly);
        PacketIO.write(imgChannel.out(), Packet.attach(result.token()));
        imgChannel.out().flush();
        hooks.afterAttachFlushed().run();
        attachImageChannel(imgChannel);
    }

    void attachImageChannel(Channel imgChannel) {
        lifecycle.atomically(tx -> {
            if (tx.isClosed() || tx.phase() != ViewerPhase.ATTACHING) return null;
            if (activationExpired(cfg.nanoClock().getAsLong())) {
                tx.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "activation timeout", true));
                return null;
            }
            imageChannel = imgChannel;
            imageChannelReady = true;
            tryStart(tx);
            return null;
        });
    }

    /**
     * Right after AUTH_RESULT(OK): enter ATTACHING with its deadline and take over the command
     * channel in one transaction; workers start and tickers register after the lock is released.
     */
    boolean attachCommandChannel(Channel ch) {
        long authOkNanos = cfg.nanoClock().getAsLong();
        return lifecycle.atomically(tx -> {
            if (!tx.advance(ViewerPhase.AUTHENTICATING, ViewerPhase.ATTACHING)) return false;
            activationDeadlineNanos = authOkNanos + timeouts.clientActivation().toNanos();
            CommandWriter w = new CommandWriter(ch.out(), CommandWriter.DEFAULT_CAPACITY, Thread::new,
                    e -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                            "command write failed", false)),
                    cfg.nanoClock());
            boolean stepSet = tx.setGracefulStep(info -> w.closeGracefully(
                    info.sendDisconnect() ? Packet.disconnect(info.reason(), info.message()) : null));
            if (!stepSet) return false; // closed: the writer must not start
            writer = w;
            commandChannel = ch;
            Heartbeat hb = new Heartbeat(w, timeouts.pingInterval(), timeouts.liveness(),
                    cfg.nanoClock().getAsLong(),
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "liveness timeout", true)));
            WriteWatchdog dog = new WriteWatchdog(w.tracker(), timeouts.cmdWriteLimit(),
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "command write stuck", false)));
            heartbeat = hb;
            lifecycle.addStopHook(w::stop); // after workers are interrupted and sockets closed
            port.atomically(stx -> { side.onAuthenticated(stx); return null; });
            tx.after(() -> setCommandReadTimeout(ch)); // before the reader starts; not under the lock
            tx.after(() -> w.start(lifecycle::startWorker));
            tx.after(() -> lifecycle.startWorker("rd-cmd-reader", () -> readLoop(ch)));
            tx.after(() -> registerTickables(hb, dog));
            return true;
        });
    }

    private void setCommandReadTimeout(Channel ch) {
        try {
            ch.socket().setSoTimeout((int) timeouts.cmdSoTimeout().toMillis());
        } catch (java.net.SocketException e) {
            lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "cannot set read timeout", false));
        }
    }

    private void registerTickables(Heartbeat hb, WriteWatchdog dog) {
        Ticker.Registration heartbeatRegistration = ticker.register(hb);
        Ticker.Registration watchdogRegistration = ticker.register(dog);
        lifecycle.addStopHook(() -> {
            heartbeatRegistration.close();
            watchdogRegistration.close();
        });
    }

    // ---- command reader --------------------------------------------------------------------

    /** Body of the {@code rd-cmd-reader} worker. */
    private void readLoop(Channel ch) {
        try {
            while (true) {
                Optional<Packet> p = PacketIO.read(ch.in(), Protocol.CMD_MAX_LEN,
                        PacketRules.readableAfterHandshake(Shell.CONNECTOR, Lane.COMMAND));
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

    void dispatch(Packet p) {
        long now = cfg.nanoClock().getAsLong();
        boolean accepted = lifecycle.atomically(tx -> {
            if (tx.isClosed()) return false;
            Stage stage = tx.phase() == ViewerPhase.ATTACHING ? Stage.PENDING : Stage.ACTIVE;
            if (!PacketRules.allows(Shell.CONNECTOR, Lane.COMMAND, stage, p.type())) {
                tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected " + p.type(), true));
                return false;
            }
            switch (p.type()) {
                case SESSION_ACTIVE -> {
                    if (activationReceived || !side.readyForActive()) {
                        tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected SESSION_ACTIVE", true));
                        return false;
                    }
                    activationReceived = true;
                    tryStart(tx);
                }
                case PING -> port.atomically(stx -> { Heartbeat.answerPing(stx, port, p); return null; });
                case PONG -> { }
                case DISCONNECT -> tx.close(new CloseInfo(p.disconnectPayload().reason(), "peer disconnected", false));
                default -> port.atomically(stx -> { side.onPacket(stx, p); return null; });
            }
            return true;
        });
        Heartbeat hb = heartbeat;
        if (accepted && hb != null) { // Heartbeat has its own lock: outside the transaction
            hb.onPacketReceived(now);
            if (p.type() == PacketType.PONG) hb.onPong(p.seq(), now);
        }
    }

    // ---- start of frame receiving ----------------------------------------------------------

    /**
     * Inside a transaction, at most once: when SESSION_ACTIVE arrived and the image channel is
     * ready (in either order) and the session is still ATTACHING, start receiving and go ACTIVE.
     */
    private boolean activationExpired(long now) {
        Long deadline = activationDeadlineNanos;
        return deadline != null && now - deadline >= 0;
    }

    private void tryStart(SessionLifecycle.Tx<ViewerPhase> tx) {
        if (tx.isClosed() || tx.phase() != ViewerPhase.ATTACHING || startAttempted) return;
        long now = cfg.nanoClock().getAsLong();
        if (activationExpired(now)) {
            tx.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "activation timeout", true));
            return;
        }
        if (!activationReceived || !imageChannelReady || !side.readyForActive()) return;
        startAttempted = true;
        if (!port.atomically(stx -> side.onActive(stx, now, imageChannel))) return;
        if (tx.advance(ViewerPhase.ATTACHING, ViewerPhase.ACTIVE)) {
            tx.after(() -> activated.complete(null));
        }
    }

    /** Close hook: fail activation first, then bridge release into the constructor-owned target. */
    private void onClosing() {
        activated.completeExceptionally(new ClosedSessionException("closed before active"));
        SessionSide.bridgeClosing(side, inputReleased);
    }
}
