package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.client.AdapterException;
import com.ltm.rd.client.AdapterFactory;
import com.ltm.rd.client.ClientConfig;
import com.ltm.rd.client.ClientHooks;
import com.ltm.rd.client.ClientPhase;
import com.ltm.rd.client.ClientSession;
import com.ltm.rd.client.InputDevice;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.ScreenGeometry;
import com.ltm.rd.client.ScreenSource;
import com.ltm.rd.client.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.server.ControlView;
import com.ltm.rd.server.InputOutcome;
import com.ltm.rd.server.ServerConfig;
import com.ltm.rd.server.ServerCore;
import com.ltm.rd.server.ServerPhase;
import com.ltm.rd.server.ServerSession;
import java.awt.Rectangle;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Owns one role of the application: the sockets, the ticker, the generation counter and the
 * executors. Swing never owns any of these. The state shown to the UI is derived from the current
 * session objects ({@link #snapshot()}), so a callback of an old session cannot overwrite the state
 * of a new one.
 *
 * <p>{@link #lock} protects only this class's own fields (slot, flags, counter). While holding it
 * nothing is called on {@code ServerCore}, {@code ClientSession}, {@code InputExecutor}, an adapter
 * or a future's completion; those are read or called after the lock was released. Codes, tokens,
 * keys and images are never logged.
 */
public final class AppRuntime implements Controller {
    private static final System.Logger LOG = System.getLogger(AppRuntime.class.getName());
    private static final Pattern CODE = Pattern.compile("\\d{" + Protocol.CODE_LEN + "}");
    private static final int INPUT_CAPACITY = 256;

    /** Test seams; {@link #none()} in production. */
    record Seams(ClientHooks hooks, SocketConnector connector, UnaryOperator<OutputStream> imageOutputDecorator,
            Runnable beforeServerStart, Runnable afterServerStart) {
        Seams {
            Objects.requireNonNull(hooks);
            Objects.requireNonNull(connector);
            Objects.requireNonNull(imageOutputDecorator);
            Objects.requireNonNull(beforeServerStart);
            Objects.requireNonNull(afterServerStart);
        }

        static Seams none() {
            return new Seams(ClientHooks.none(), SocketConnector.DEFAULT, UnaryOperator.identity(), () -> { },
                    () -> { });
        }
    }

    private enum ServerState { IDLE, STARTING, STARTED, FAILED }

    /** One connect attempt, from the accepted call until the session is replaced. Guarded by {@link #lock}. */
    private static final class ClientSlot {
        final long generation;
        final String host;
        ClientSession session; // null while the adapters are prepared
        boolean cancelled;
        boolean ended; // no session will ever exist for this slot (adapter failure or cancelled early)
        String failure = "";
        int screenWidth;
        int screenHeight;

        ClientSlot(long generation, String host) {
            this.generation = generation;
            this.host = host;
        }

        SlotView view() {
            return new SlotView(generation, host, session, cancelled, ended, failure, screenWidth, screenHeight);
        }
    }

    private record SlotView(long generation, String host, ClientSession session, boolean cancelled, boolean ended,
            String failure, int screenWidth, int screenHeight) {}

    private final LaunchOptions.Role role;
    private final AppConfig cfg;
    private final Seams seams;
    private final AdapterFactory adapters; // null for the Server
    private final Object lock = new Object();
    private final ExecutorService app = Executors.newSingleThreadExecutor(daemonFactory("rd-app", false));
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CompletableFuture<ShutdownReport> shutdownFuture = new CompletableFuture<>();

    // Client resources; null for the Server
    private final Ticker ticker;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService cleanup;
    private final InputExecutor inputExecutor;
    private volatile Throwable lastInputFault;
    private volatile ScreenSource currentScreen;

    // guarded by lock
    private long generationCounter;
    private ClientSlot slot;
    private ServerCore core;
    private ServerState serverState = ServerState.IDLE;
    private String serverMessage = "";

    private AppRuntime(LaunchOptions.Role role, AppConfig cfg, AdapterFactory adapters, Seams seams)
            throws AdapterException {
        this.role = role;
        this.cfg = Objects.requireNonNull(cfg);
        this.seams = Objects.requireNonNull(seams);
        this.adapters = adapters;
        if (role == LaunchOptions.Role.CLIENT) {
            InputDevice device = adapters.inputDevice(); // before any thread exists: a failure leaves nothing behind
            Ticker t = new Ticker(cfg.timeouts().tick(), cfg.nanoClock());
            ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(daemonFactory("rd-close-timer", false));
            ExecutorService clean = Executors.newCachedThreadPool(daemonFactory("rd-cleanup-", true));
            InputExecutor executor;
            try {
                executor = new InputExecutor(device, this::currentBounds, INPUT_CAPACITY,
                        InputExecutor.defaultThreadFactory(), this::onInputFault);
            } catch (RuntimeException e) {
                t.close();
                sched.shutdownNow();
                clean.shutdownNow();
                throw e;
            }
            this.ticker = t;
            this.scheduler = sched;
            this.cleanup = clean;
            this.inputExecutor = executor;
        } else {
            this.ticker = null;
            this.scheduler = null;
            this.cleanup = null;
            this.inputExecutor = null;
        }
    }

    /** A Server runtime; performs no I/O until {@link #startServer()}. */
    public static AppRuntime forServer(AppConfig cfg) {
        return forServer(cfg, Seams.none());
    }

    static AppRuntime forServer(AppConfig cfg, Seams seams) {
        try {
            return new AppRuntime(LaunchOptions.Role.SERVER, cfg, null, seams);
        } catch (AdapterException e) {
            throw new IllegalStateException(e); // a Server asks no adapter
        }
    }

    /** A Client runtime; fails with {@link AdapterException} before any thread was started. */
    public static AppRuntime forClient(AppConfig cfg, AdapterFactory adapters) throws AdapterException {
        return forClient(cfg, adapters, Seams.none());
    }

    static AppRuntime forClient(AppConfig cfg, AdapterFactory adapters, Seams seams) throws AdapterException {
        return new AppRuntime(LaunchOptions.Role.CLIENT, cfg, Objects.requireNonNull(adapters), seams);
    }

    public LaunchOptions.Role role() {
        return role;
    }

    // ---- test accessors ------------------------------------------------------------------------

    /** Live ticker registrations: the Client runtime's own ticker or the running ServerCore's. */
    int tickerRegistrations() {
        if (role == LaunchOptions.Role.CLIENT) return ticker.registrations();
        ServerCore c;
        synchronized (lock) {
            c = core;
        }
        return c == null ? 0 : c.tickerRegistrations();
    }

    InputExecutor inputExecutor() {
        return inputExecutor;
    }

    Optional<ClientSession> clientSession() {
        synchronized (lock) {
            return Optional.ofNullable(slot == null ? null : slot.session);
        }
    }

    ServerCore serverCore() {
        synchronized (lock) {
            return core;
        }
    }

    // ---- Server --------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> startServer() {
        if (role != LaunchOptions.Role.SERVER) {
            return CompletableFuture.failedFuture(new IllegalStateException("not a server runtime"));
        }
        boolean accepted;
        synchronized (lock) {
            accepted = !shuttingDown.get() && (serverState == ServerState.IDLE || serverState == ServerState.FAILED);
            if (accepted) {
                serverState = ServerState.STARTING;
                serverMessage = "";
            }
        }
        if (!accepted) {
            return CompletableFuture.failedFuture(new IllegalStateException("server cannot be started now"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            app.execute(() -> runServerStart(result));
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("shutting down"));
        }
        return result;
    }

    /** Body on {@code rd-app}: the core is published before it binds, so shutdown always sees it. */
    private void runServerStart(CompletableFuture<Void> result) {
        ServerCore c = null;
        try {
            c = new ServerCore(new ServerConfig(cfg.bindAddress(), cfg.cmdPort(), cfg.imgPort(), cfg.timeouts(),
                    cfg.nanoClock(), new SecureRandom()));
            boolean proceed;
            synchronized (lock) {
                proceed = !shuttingDown.get();
                if (proceed) core = c;
            }
            if (!proceed) throw new IllegalStateException("shutting down");
            seams.beforeServerStart().run();
            if (shuttingDown.get()) throw new IllegalStateException("shutting down");
            c.start();
            synchronized (lock) {
                serverState = ServerState.STARTED;
            }
            seams.afterServerStart().run();
            result.complete(null);
        } catch (Throwable e) {
            if (c != null) c.close();
            synchronized (lock) {
                if (core == c) core = null;
                if (!shuttingDown.get()) {
                    serverState = ServerState.FAILED;
                    serverMessage = Messages.forThrowable(e);
                }
            }
            LOG.log(System.Logger.Level.WARNING, "server start failed: " + e.getClass().getSimpleName());
            result.completeExceptionally(e);
        }
    }

    @Override
    public boolean regenerateCode() {
        ServerCore c = startedCore();
        return c != null && c.sessions().regenerateCode();
    }

    /** The running core, or null when this is not a started Server or it is shutting down. */
    private ServerCore startedCore() {
        if (role != LaunchOptions.Role.SERVER || shuttingDown.get()) return null;
        synchronized (lock) {
            return serverState == ServerState.STARTED ? core : null;
        }
    }

    @Override
    public SendResult sendInput(long generation, long inputEpoch, Packet inputPacket) {
        if (role != LaunchOptions.Role.SERVER) return SendResult.WRONG_ROLE;
        if (shuttingDown.get()) return SendResult.SHUTTING_DOWN;
        ServerCore c = startedCore();
        if (c == null) return SendResult.NO_SESSION;
        ServerSession session = c.sessions().current().orElse(null); // read once: the object decides, not a lookup
        if (session == null) return SendResult.NO_SESSION;
        if (session.generation() != generation) return SendResult.STALE_GENERATION;
        InputOutcome outcome = session.offerInput(inputEpoch, inputPacket);
        return switch (outcome) {
            case SENT -> SendResult.SENT;
            case GATE_CLOSED -> SendResult.GATE_CLOSED;
            case STALE_EPOCH -> SendResult.STALE_EPOCH;
            case OVERFLOW -> SendResult.OVERFLOW;
        };
    }

    @Override
    public boolean releaseControlInput(long generation) {
        ServerCore c = startedCore();
        if (c == null) return false;
        ServerSession session = c.sessions().current().orElse(null);
        return session != null && session.generation() == generation
                && session.releaseControlInput() == CommandWriter.OfferResult.ACCEPTED;
    }

    // ---- Client --------------------------------------------------------------------------------

    @Override
    public ConnectOutcome connect(String host, String code) {
        if (role != LaunchOptions.Role.CLIENT) return ConnectOutcome.WRONG_ROLE;
        if (shuttingDown.get()) return ConnectOutcome.SHUTTING_DOWN;
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.isEmpty() || code == null || !CODE.matcher(code).matches()) return ConnectOutcome.INVALID_INPUT;
        while (true) {
            if (inputFaulted()) return ConnectOutcome.INPUT_FAULTED;
            SlotView seen;
            synchronized (lock) {
                seen = slot == null ? null : slot.view();
            }
            ConnectOutcome blocked = blockedBy(seen); // reads session state: outside the lock
            if (blocked != null) return blocked;
            ClientSlot created = null;
            synchronized (lock) {
                if (shuttingDown.get()) return ConnectOutcome.SHUTTING_DOWN;
                SlotView now = slot == null ? null : slot.view();
                if (Objects.equals(now, seen)) {
                    created = new ClientSlot(++generationCounter, cleanHost);
                    slot = created;
                }
            }
            if (created == null) continue; // the slot changed while we looked: decide again
            ClientSlot started = created;
            try {
                app.execute(() -> prepareAndStart(started, cleanHost, code));
            } catch (RejectedExecutionException e) {
                endSlot(started, "");
                return ConnectOutcome.SHUTTING_DOWN;
            }
            return ConnectOutcome.STARTED;
        }
    }

    /** Why a new connect must wait for the previous slot, or null when the slot is free. */
    private ConnectOutcome blockedBy(SlotView v) {
        if (v == null || v.ended()) return null;
        ClientSession cs = v.session();
        if (cs == null || !cs.lifecycle().isClosed()) return ConnectOutcome.BUSY;
        CompletableFuture<Void> released = cs.inputReleased();
        if (released.isCompletedExceptionally()) return ConnectOutcome.INPUT_FAULTED;
        if (!(cs.terminated().isDone() && released.isDone())) return ConnectOutcome.WAITING_RELEASE;
        return null;
    }

    private boolean inputFaulted() {
        return lastInputFault != null || inputExecutor.state() == InputExecutor.State.FAULTED;
    }

    /** Body on {@code rd-app}: adapters first, then the session; the session is started outside the lock. */
    private void prepareAndStart(ClientSlot s, String host, String code) {
        try {
            if (abandoned(s)) {
                endCancelled(s);
                return;
            }
            adapters.refreshInputDevice();
            ScreenSource source = adapters.newScreenSource();
            ScreenGeometry geometry = source.geometry();
            currentScreen = source;
            ClientConfig clientCfg = new ClientConfig(host, cfg.cmdPort(), cfg.imgPort(), code, cfg.timeouts(),
                    cfg.nanoClock(), seams.imageOutputDecorator());
            ClientSession session = new ClientSession(clientCfg, s.generation, inputExecutor, source,
                    seams.connector(), seams.hooks(), ticker, scheduler, cleanup);
            boolean start;
            synchronized (lock) {
                start = !s.cancelled && !shuttingDown.get();
                if (start) {
                    s.session = session;
                    s.screenWidth = geometry.bounds().width;
                    s.screenHeight = geometry.bounds().height;
                }
            }
            if (start) {
                session.start();
            } else {
                endCancelled(s);
            }
        } catch (Throwable e) { // AdapterException or anything unexpected: the slot ends, the app stays usable
            LOG.log(System.Logger.Level.WARNING, "connect preparation failed: " + e.getClass().getSimpleName());
            endSlot(s, Messages.forThrowable(e));
        }
    }

    private boolean abandoned(ClientSlot s) {
        synchronized (lock) {
            return s.cancelled || shuttingDown.get();
        }
    }

    private void endCancelled(ClientSlot s) {
        endSlot(s, shuttingDown.get() ? "" : Messages.forClose(Packet.DisconnectReason.USER));
    }

    private void endSlot(ClientSlot s, String message) {
        synchronized (lock) {
            s.ended = true;
            s.failure = message;
        }
    }

    @Override
    public boolean cancel() {
        if (role != LaunchOptions.Role.CLIENT) return false;
        ClientSession cs;
        synchronized (lock) {
            if (slot == null || slot.ended) return false;
            if (slot.session == null) { // still preparing: rd-app will see the flag and create nothing
                if (slot.cancelled) return false;
                slot.cancelled = true;
                return true;
            }
            cs = slot.session;
        }
        ClientPhase phase = cs.phase();
        boolean preActive = phase == ClientPhase.CONNECTING || phase == ClientPhase.AUTHENTICATING
                || phase == ClientPhase.ATTACHING;
        if (!preActive) return false;
        cs.cancel();
        return true;
    }

    // ---- both roles ----------------------------------------------------------------------------

    @Override
    public boolean disconnect() {
        if (shuttingDown.get()) return false;
        if (role == LaunchOptions.Role.SERVER) {
            ServerCore c = startedCore();
            ServerSession session = c == null ? null : c.sessions().current().orElse(null);
            if (session == null || session.closeInfo().isPresent()) return false;
            session.disconnect();
            synchronized (lock) {
                serverMessage = Messages.forClose(Packet.DisconnectReason.USER);
            }
            return true;
        }
        ClientSession cs;
        synchronized (lock) {
            if (slot == null || slot.ended) return false;
            if (slot.session == null) {
                if (slot.cancelled) return false;
                slot.cancelled = true;
                return true;
            }
            cs = slot.session;
        }
        if (cs.lifecycle().isClosed()) return false;
        cs.cancel(); // closes with USER and tells the Server
        return true;
    }

    // ---- snapshot ------------------------------------------------------------------------------

    @Override
    public Snapshot snapshot() {
        if (shuttingDown.get()) {
            return blank(UiPhase.SHUTTING_DOWN, Messages.forClose(Packet.DisconnectReason.SHUTDOWN));
        }
        return role == LaunchOptions.Role.SERVER ? serverSnapshot() : clientSnapshot();
    }

    /** A snapshot without a session: the configured ports, nothing else. */
    private Snapshot blank(UiPhase phase, String message) {
        return new Snapshot(role, phase, 0, 0, message, null, cfg.cmdPort(), cfg.imgPort(), null, 0, 0, null, 0,
                false, TrafficStats.ZERO);
    }

    private Snapshot serverSnapshot() {
        ServerCore c;
        ServerState state;
        String message;
        synchronized (lock) {
            c = core;
            state = serverState;
            message = serverMessage;
        }
        switch (state) {
            case IDLE:
                return blank(UiPhase.IDLE, message);
            case STARTING:
                return blank(UiPhase.STARTING, message);
            case FAILED:
                return blank(UiPhase.FAILED, message);
            default:
                break;
        }
        int cmdPort = c.cmdPort();
        int imgPort = c.imgPort();
        String code = c.sessions().currentCode();
        ServerSession session = c.sessions().current().orElse(null);
        if (session == null) {
            return new Snapshot(role, UiPhase.LISTENING, 0, 0, message, null, cmdPort, imgPort, code, 0, 0, null, 0,
                    false, TrafficStats.ZERO);
        }
        ControlView view = session.controlView();
        Optional<CloseInfo> closed = session.closeInfo();
        UiPhase phase;
        String shownMessage;
        if (closed.isPresent() || view.phase() == ServerPhase.CLOSING) {
            phase = UiPhase.CLOSING;
            shownMessage = closed.map(i -> Messages.forClose(i.reason())).orElse("");
            code = null;
            rememberServerMessage(shownMessage);
        } else {
            phase = view.phase() == ServerPhase.ACTIVE ? UiPhase.ACTIVE : UiPhase.RESERVED;
            shownMessage = "";
            rememberServerMessage("");
        }
        Optional<Packet.SizePayload> screen = session.screen();
        TrafficSnapshot t = session.traffic();
        OptionalLong rtt = session.rttNanos();
        return new Snapshot(role, phase, view.generation(), view.inputEpoch(), shownMessage,
                session.peer().getHostAddress(), cmdPort, imgPort, code, screen.map(Packet.SizePayload::width).orElse(0),
                screen.map(Packet.SizePayload::height).orElse(0), view.frame(), view.framesReceived(),
                view.controlEnabled(), stats(t, rtt));
    }

    private void rememberServerMessage(String message) {
        synchronized (lock) {
            if (serverState == ServerState.STARTED) serverMessage = message;
        }
    }

    private Snapshot clientSnapshot() {
        SlotView v;
        synchronized (lock) {
            v = slot == null ? null : slot.view();
        }
        if (v == null || v.ended()) {
            if (inputFaulted()) return blank(UiPhase.FAILED, Messages.INPUT_RELEASE_FAILED);
            return blank(UiPhase.IDLE, v == null ? "" : v.failure());
        }
        ClientSession cs = v.session();
        if (cs == null) { // adapters are being prepared
            return clientSnapshot(UiPhase.CONNECTING, v, "", TrafficSnapshot.ZERO, OptionalLong.empty(), 0);
        }
        TrafficSnapshot t = cs.traffic();
        OptionalLong rtt = cs.rttNanos();
        long frames = cs.framesSent();
        boolean closed = cs.lifecycle().isClosed();
        if (!closed) {
            UiPhase phase = switch (cs.phase()) {
                case CONNECTING -> UiPhase.CONNECTING;
                case AUTHENTICATING -> UiPhase.AUTHENTICATING;
                case ATTACHING -> UiPhase.ATTACHING;
                case ACTIVE -> UiPhase.ACTIVE;
                case CLOSING -> UiPhase.CLOSING;
            };
            return clientSnapshot(phase, v, "", t, rtt, frames);
        }
        CompletableFuture<Void> released = cs.inputReleased();
        if (released.isCompletedExceptionally()) {
            return blank(UiPhase.FAILED, Messages.INPUT_RELEASE_FAILED);
        }
        String message = closeMessage(cs);
        if (cs.terminated().isDone() && released.isDone()) {
            return blank(UiPhase.IDLE, message);
        }
        return clientSnapshot(UiPhase.CLOSING, v, message, t, rtt, frames);
    }

    private Snapshot clientSnapshot(UiPhase phase, SlotView v, String message, TrafficSnapshot t, OptionalLong rtt,
            long frames) {
        return new Snapshot(role, phase, v.generation(), 0, message, v.host(), cfg.cmdPort(), cfg.imgPort(), null,
                v.screenWidth(), v.screenHeight(), null, frames, false, stats(t, rtt));
    }

    private static String closeMessage(ClientSession cs) {
        Optional<Packet.AuthStatus> auth = cs.authFailure();
        if (auth.isPresent()) return Messages.forAuth(auth.get());
        return cs.lifecycle().closeInfo().map(i -> Messages.forClose(i.reason())).orElse("");
    }

    private static TrafficStats stats(TrafficSnapshot t, OptionalLong rtt) {
        return new TrafficStats(t.cmdIn(), t.cmdOut(), t.imgIn(), t.imgOut(), rtt.orElse(TrafficStats.NO_RTT));
    }

    // ---- input executor callbacks ------------------------------------------------------------------

    private Rectangle currentBounds() {
        ScreenSource source = currentScreen;
        return source == null ? new Rectangle() : source.geometry().bounds();
    }

    private void onInputFault(Throwable t) {
        lastInputFault = t;
        LOG.log(System.Logger.Level.WARNING, "input device fault: " + t.getClass().getSimpleName());
    }

    // ---- shutdown --------------------------------------------------------------------------------

    @Override
    public CompletableFuture<ShutdownReport> shutdownAsync() {
        if (shuttingDown.compareAndSet(false, true)) {
            Thread t = new Thread(this::runShutdown, "rd-shutdown");
            t.setDaemon(true);
            t.start();
        }
        return shutdownFuture;
    }

    /**
     * Closes the session, then the core, then the input executor and the shared executors, each wait
     * limited by what is left of the budget. Whatever did not finish is reported, not assumed.
     */
    private void runShutdown() {
        long deadline = System.nanoTime() + cfg.shutdownBudget().toNanos();
        boolean socketsClosed = true;
        boolean workersStopped = true;
        InputRelease release = InputRelease.NOT_NEEDED;
        try {
            app.shutdown(); // no new work; a running start or prepare sees the flag
            try {
                if (!app.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS)) app.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                app.shutdownNow();
            }
            if (role == LaunchOptions.Role.SERVER) {
                ServerCore c;
                synchronized (lock) {
                    c = core;
                }
                if (c != null) {
                    ServerSession session = c.sessions().current().orElse(null);
                    c.close();
                    if (session != null) {
                        socketsClosed = session.socketsClosed().isDone();
                        workersStopped = session.terminated().isDone();
                    }
                }
            } else {
                ClientSession cs;
                synchronized (lock) {
                    cs = slot == null ? null : slot.session;
                }
                if (cs != null) {
                    cs.lifecycle().close(new CloseInfo(Packet.DisconnectReason.SHUTDOWN, "app shutting down", true));
                    await(cs.socketsClosed(), deadline);
                    await(cs.terminated(), deadline);
                    await(cs.inputReleased(), deadline);
                    socketsClosed = cs.socketsClosed().isDone();
                    workersStopped = cs.terminated().isDone();
                    CompletableFuture<Void> released = cs.inputReleased();
                    if (cs.inputBeginCount() > 0) {
                        release = released.isCompletedExceptionally() ? InputRelease.FAILED
                                : released.isDone() ? InputRelease.RELEASED : InputRelease.UNCONFIRMED;
                    }
                }
                CompletableFuture<Void> executorStopped = inputExecutor.shutdownAsync();
                await(executorStopped, deadline);
                workersStopped &= executorStopped.isDone();
                ticker.close();
                scheduler.shutdownNow();
                cleanup.shutdownNow();
            }
            shutdownFuture.complete(new ShutdownReport(socketsClosed, workersStopped, release));
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "shutdown failed: " + e.getClass().getSimpleName());
            shutdownFuture.complete(new ShutdownReport(false, false, InputRelease.UNCONFIRMED));
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1, deadline - System.nanoTime());
    }

    private static void await(CompletableFuture<?> future, long deadline) {
        try {
            future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // the caller inspects the future's state; a failure or a timeout is reported, not thrown
        }
    }

    private static ThreadFactory daemonFactory(String prefix, boolean numbered) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, numbered ? prefix + seq.incrementAndGet() : prefix);
            t.setDaemon(true);
            return t;
        };
    }
}
