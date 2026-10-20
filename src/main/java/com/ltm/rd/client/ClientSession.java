package com.ltm.rd.client;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.ClosedSessionException;
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
import com.ltm.rd.common.TrafficCounter;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.common.WriteWatchdog;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client side of one session. Handshake order (spec 1.4): command socket, AUTH, AUTH_RESULT,
 * command channel hand-over with SCREEN_INFO, image socket, ATTACH. Input starts exactly once,
 * inside a transaction, when SESSION_ACTIVE has arrived and the image channel is ready. Codes and
 * tokens are never logged.
 */
public final class ClientSession implements Tickable {
    private static final int IMAGE_SEND_BUFFER = 64 * 1024;
    private static final int FPS = 12;
    private static final float FRAME_QUALITY = 0.6f;

    /** What the Server may send to the Client on the command channel. */
    private static final Set<PacketType> COMMAND_IN = EnumSet.of(PacketType.SESSION_ACTIVE, PacketType.PING,
            PacketType.PONG, PacketType.DISCONNECT, PacketType.MOUSE_MOVE, PacketType.MOUSE_PRESS,
            PacketType.MOUSE_RELEASE, PacketType.WHEEL, PacketType.KEY_PRESS, PacketType.KEY_RELEASE,
            PacketType.RELEASE_ALL);

    private final ClientConfig cfg;
    private final Timeouts timeouts;
    private final long generation;
    private final InputExecutor input;
    private final ScreenSource screen;
    private final SocketConnector connector;
    private final ClientHooks hooks;
    private final Ticker ticker;
    private final SessionLifecycle<ClientPhase> lifecycle;

    private final AtomicBoolean startRequested = new AtomicBoolean();
    private final CompletableFuture<Void> activated = new CompletableFuture<>();
    private final CompletableFuture<Void> inputReleased = new CompletableFuture<>();
    private final AtomicReference<Packet.AuthStatus> authFailure = new AtomicReference<>();
    private final AtomicInteger inputBegins = new AtomicInteger();
    private final AtomicInteger senderRuns = new AtomicInteger();

    // guarded by the lifecycle lock (read and written only inside atomically)
    private boolean activationReceived;
    private boolean imageChannelReady;
    private boolean startAttempted;
    private boolean inputBegun;
    private CommandWriter writer;
    private CommandReceiver receiver;
    private Channel imageChannel;
    private Channel commandChannel;
    private volatile ScreenSender sender;
    private volatile ScreenGeometry announced; // the geometry SCREEN_INFO was derived from

    private volatile Heartbeat heartbeat;
    private volatile Long authDeadlineNanos; // set when AUTHENTICATING begins
    private volatile Long activationDeadlineNanos; // set when ATTACHING begins

    public ClientSession(ClientConfig cfg, long generation, InputExecutor input, ScreenSource screen,
            SocketConnector connector, ClientHooks hooks, Ticker ticker, ScheduledExecutorService scheduler,
            Executor cleanup) {
        this.cfg = Objects.requireNonNull(cfg);
        this.timeouts = cfg.timeouts();
        this.generation = generation;
        this.input = Objects.requireNonNull(input);
        this.screen = Objects.requireNonNull(screen);
        this.connector = Objects.requireNonNull(connector);
        this.hooks = Objects.requireNonNull(hooks);
        this.ticker = Objects.requireNonNull(ticker);
        this.lifecycle = new SessionLifecycle<>(ClientPhase.CONNECTING, ClientPhase.CLOSING, cfg.nanoClock(),
                timeouts.closeGrace(), scheduler, cleanup);
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

    public ClientPhase phase() {
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
     * Completes when the input side has released everything this session held (immediately on
     * close if input never began). Never put {@code orTimeout} on it: it is the proof of release.
     */
    public CompletableFuture<Void> inputReleased() {
        return inputReleased;
    }

    public long releaseAllReceived() {
        CommandReceiver r = lifecycle.atomically(tx -> receiver);
        return r == null ? 0 : r.releaseAllReceived();
    }

    public SessionLifecycle<ClientPhase> lifecycle() {
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

    /** Frames the screen sender wrote and flushed. */
    public long framesSent() {
        ScreenSender s = sender;
        return s == null ? 0 : s.framesSent();
    }

    // Observability for tests: how far the two start conditions and the start itself got.

    public boolean activationReceived() {
        return lifecycle.atomically(tx -> activationReceived);
    }

    public boolean imageChannelReady() {
        return lifecycle.atomically(tx -> imageChannelReady);
    }

    /** Number of times input began for this session; must never exceed one. */
    public int inputBeginCount() {
        return inputBegins.get();
    }

    /** Number of times the screen sender worker body ran. */
    public int senderRuns() {
        return senderRuns.get();
    }

    // ---- deadlines -------------------------------------------------------------------------

    /** Phase deadlines: the check and the close are one decision inside {@code closeIf}. */
    @Override
    public void tick(long nowNanos) {
        Long auth = authDeadlineNanos;
        if (auth != null && nowNanos - auth >= 0) {
            lifecycle.closeIf(ClientPhase.AUTHENTICATING,
                    new CloseInfo(Packet.DisconnectReason.TIMEOUT, "auth timeout", true));
        }
        Long activation = activationDeadlineNanos;
        if (activation != null && nowNanos - activation >= 0) {
            lifecycle.closeIf(ClientPhase.ATTACHING,
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
        if (!connect(cmd, cfg.cmdPort())) return;

        boolean entered = lifecycle.atomically(tx -> {
            if (!tx.advance(ClientPhase.CONNECTING, ClientPhase.AUTHENTICATING)) return false;
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
            reply = PacketIO.read(cmdChannel.in(), Protocol.CMD_MAX_LEN, EnumSet.of(PacketType.AUTH_RESULT));
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

        ScreenGeometry geometry = screen.geometry(); // read before the transaction: no work under the lock
        announced = geometry;
        Rectangle bounds = geometry.bounds();
        if (!attachCommandChannel(cmdChannel, bounds)) return;

        Socket img = lifecycle.register(new Socket());
        img.setSendBufferSize(IMAGE_SEND_BUFFER);
        if (!connect(img, cfg.imgPort())) return;
        Channel imgChannel = Channel.wrap(img, new TrafficCounter(), cfg.imageOutputDecorator());
        lifecycle.addStopHook(imgChannel::closeWrappersQuietly);
        PacketIO.write(imgChannel.out(), Packet.attach(result.token()));
        imgChannel.out().flush();
        hooks.afterAttachFlushed().run();
        lifecycle.atomically(tx -> {
            if (tx.isClosed()) return null;
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
    private boolean attachCommandChannel(Channel ch, Rectangle bounds) {
        return lifecycle.atomically(tx -> {
            if (!tx.advance(ClientPhase.AUTHENTICATING, ClientPhase.ATTACHING)) return false;
            activationDeadlineNanos = cfg.nanoClock().getAsLong() + timeouts.clientActivation().toNanos();
            CommandWriter w = new CommandWriter(ch.out(), CommandWriter.DEFAULT_CAPACITY, Thread::new,
                    e -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                            "command write failed", false)),
                    cfg.nanoClock());
            boolean stepSet = tx.setGracefulStep(info -> w.closeGracefully(
                    info.sendDisconnect() ? Packet.disconnect(info.reason(), info.message()) : null));
            if (!stepSet) return false; // closed: the writer must not start
            writer = w;
            commandChannel = ch;
            receiver = new CommandReceiver(input, generation, bounds, w);
            Heartbeat hb = new Heartbeat(w, timeouts.pingInterval(), timeouts.liveness(),
                    cfg.nanoClock().getAsLong(),
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "liveness timeout", true)));
            WriteWatchdog dog = new WriteWatchdog(w.tracker(), timeouts.cmdWriteLimit(),
                    () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "command write stuck", false)));
            heartbeat = hb;
            lifecycle.addStopHook(w::stop); // after workers are interrupted and sockets closed
            w.offer(Packet.screenInfo(bounds.width, bounds.height));
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

    private void dispatch(Packet p) {
        long now = cfg.nanoClock().getAsLong();
        boolean accepted = lifecycle.atomically(tx -> {
            if (tx.isClosed()) return false;
            switch (p.type()) {
                case SESSION_ACTIVE -> {
                    if (tx.phase() != ClientPhase.ATTACHING || activationReceived) {
                        tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected SESSION_ACTIVE", true));
                        return false;
                    }
                    activationReceived = true;
                    tryStart(tx);
                }
                case PONG -> { }
                case DISCONNECT -> tx.close(new CloseInfo(p.disconnectPayload().reason(), "peer disconnected", false));
                default -> receiver.handle(tx, p); // PING and the input commands
            }
            return true;
        });
        Heartbeat hb = heartbeat;
        if (accepted && hb != null) { // Heartbeat has its own lock: outside the transaction
            hb.onPacketReceived(now);
            if (p.type() == PacketType.PONG) hb.onPong(p.seq(), now);
        }
    }

    // ---- start of input --------------------------------------------------------------------

    /**
     * Inside a transaction, at most once: when SESSION_ACTIVE arrived and the image channel is
     * ready (in either order) and the session is still ATTACHING, begin input and go ACTIVE.
     */
    private void tryStart(SessionLifecycle.Tx<ClientPhase> tx) {
        if (startAttempted) return;
        if (!activationReceived || !imageChannelReady) return;
        if (tx.isClosed() || tx.phase() != ClientPhase.ATTACHING) return;
        startAttempted = true;
        if (!input.beginSession(generation)) {
            tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "input session refused", true));
            return;
        }
        inputBegun = true;
        inputBegins.incrementAndGet();
        tx.advance(ClientPhase.ATTACHING, ClientPhase.ACTIVE);
        tx.after(() -> activated.complete(null));
        tx.after(this::startSender);
    }

    /** Hook point for the screen sender; the sender itself arrives with the image stream. */
    private void startSender() {
        hooks.beforeSenderStart().run(); // lets a test cancel between "input began" and "sender started"
        Channel img = lifecycle.atomically(tx -> imageChannel);
        ScreenSender sender = new ScreenSender(this, img, screen,
                new FrameEncoder(Protocol.MAX_FRAME_WIDTH, FRAME_QUALITY), FPS, cfg.nanoClock());
        this.sender = sender;
        WriteWatchdog dog = new WriteWatchdog(sender.tracker(), timeouts.imgWriteLimit(),
                () -> lifecycle.close(new CloseInfo(Packet.DisconnectReason.TIMEOUT, "image write stuck", true)));
        Ticker.Registration registration = ticker.register(dog);
        lifecycle.addStopHook(registration::close); // closed at once if the session is already closed
        Runnable body = sender.body();
        lifecycle.startWorker("rd-screen-sender", () -> {
            senderRuns.incrementAndGet();
            body.run();
        });
    }

    /** Geometry announced in SCREEN_INFO; the sender compares against it. */
    ScreenGeometry announcedGeometry() {
        return announced;
    }

    /** Close hook: release input for this generation, if it began, and publish the result. */
    private void onClosing() {
        boolean begun = lifecycle.atomically(tx -> inputBegun);
        activated.completeExceptionally(new ClosedSessionException("closed before active"));
        if (!begun) {
            inputReleased.complete(null);
            return;
        }
        input.endSession(generation).whenComplete((v, e) -> {
            if (e != null) inputReleased.completeExceptionally(e);
            else inputReleased.complete(null);
        });
    }
}
