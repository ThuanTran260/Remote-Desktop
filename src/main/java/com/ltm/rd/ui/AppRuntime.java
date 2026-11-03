package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.viewer.ViewerConfig;
import com.ltm.rd.viewer.ViewerHooks;
import com.ltm.rd.viewer.ViewerPhase;
import com.ltm.rd.viewer.ViewerSession;
import com.ltm.rd.viewer.SocketConnector;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.TrafficSnapshot;
import com.ltm.rd.host.AdapterException;
import com.ltm.rd.host.AdapterFactory;
import com.ltm.rd.host.InputDevice;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.host.ScreenGeometry;
import com.ltm.rd.host.HostConfig;
import com.ltm.rd.host.HostCore;
import com.ltm.rd.host.HostPhase;
import com.ltm.rd.host.HostSession;
import com.ltm.rd.viewer.ControlView;
import com.ltm.rd.viewer.InputOutcome;
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
 * nothing is called on {@code HostCore}, {@code ViewerSession}, {@code InputExecutor}, an adapter
 * or a future's completion; those are read or called after the lock was released. Codes, tokens,
 * keys and images are never logged.
 */
public final class AppRuntime implements Controller {
    private static final System.Logger LOG = System.getLogger(AppRuntime.class.getName());
    private static final Pattern CODE = Pattern.compile("\\d{" + Protocol.CODE_LEN + "}");
    private static final int INPUT_CAPACITY = 256;

    /** Test seams; {@link #none()} in production. */
    record Seams(ViewerHooks hooks, SocketConnector connector, UnaryOperator<OutputStream> imageOutputDecorator,
            Runnable beforeHostStart, Runnable afterHostStart, Runnable beforeSenderStart) {
        Seams {
            Objects.requireNonNull(hooks);
            Objects.requireNonNull(connector);
            Objects.requireNonNull(imageOutputDecorator);
            Objects.requireNonNull(beforeHostStart);
            Objects.requireNonNull(afterHostStart);
            Objects.requireNonNull(beforeSenderStart);
        }

        static Seams none() {
            return new Seams(ViewerHooks.none(), SocketConnector.DEFAULT, UnaryOperator.identity(), () -> { },
                    () -> { }, () -> { });
        }
    }

    private enum ServerState { IDLE, STARTING, STARTED, FAILED }

    /** One connect attempt, from the accepted call until the session is replaced. Guarded by {@link #lock}. */
    private static final class ClientSlot {
        final long generation;
        final String host;
        ViewerSession session; // null until the queued connect attempt is constructed
        boolean cancelled;
        boolean ended; // no session will ever exist for this slot (startup failure or cancelled early)
        String failure = "";

        ClientSlot(long generation, String host) {
            this.generation = generation;
            this.host = host;
        }

        SlotView view() {
            return new SlotView(generation, host, session, cancelled, ended, failure);
        }
    }

    private record SlotView(long generation, String host, ViewerSession session, boolean cancelled, boolean ended,
            String failure) {}

    private final LaunchOptions.Role role;
    private final AppConfig cfg;
    private final Seams seams;
    private final Runnable afterHostSnapshotProjection;
    private final Runnable beforeHostDisconnectClose;
    private final AdapterFactory adapters; // null for the Viewer
    private final Object lock = new Object();
    private final ExecutorService app = Executors.newSingleThreadExecutor(daemonFactory("rd-app", false));
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CompletableFuture<ShutdownReport> shutdownFuture = new CompletableFuture<>();

    // Viewer connection resources; null for the Host. The Host owns inputExecutor instead.
    private final Ticker ticker;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService cleanup;
    private final InputExecutor inputExecutor;
    private volatile Throwable lastInputFault;
    private volatile ScreenGeometry boundsSnapshot;
    private volatile HostSession faultClosedSession;

    // guarded by lock
    private long generationCounter;
    private ClientSlot slot;
    private HostCore core;
    private ServerState serverState = ServerState.IDLE;
    private String serverMessage = "";
    private HostSession observedSession; // the newest Host session observed at reservation
    private long observedGeneration;

    private AppRuntime(LaunchOptions.Role role, AppConfig cfg, AdapterFactory adapters, Seams seams)
            throws AdapterException {
        this(role, cfg, adapters, seams, () -> { }, () -> { });
    }

    private AppRuntime(LaunchOptions.Role role, AppConfig cfg, AdapterFactory adapters, Seams seams,
            Runnable afterHostSnapshotProjection, Runnable beforeHostDisconnectClose) throws AdapterException {
        this.role = role;
        this.cfg = Objects.requireNonNull(cfg);
        this.seams = Objects.requireNonNull(seams);
        this.afterHostSnapshotProjection = Objects.requireNonNull(afterHostSnapshotProjection);
        this.beforeHostDisconnectClose = Objects.requireNonNull(beforeHostDisconnectClose);
        this.adapters = adapters;
        if (role == LaunchOptions.Role.HOST) {
            InputDevice device = Objects.requireNonNull(adapters).inputDevice();
            this.inputExecutor = new InputExecutor(device, this::currentBounds, INPUT_CAPACITY,
                    InputExecutor.defaultThreadFactory(), this::onInputFault);
            this.ticker = null;
            this.scheduler = null;
            this.cleanup = null;
        } else {
            this.inputExecutor = null;
            Ticker createdTicker = new Ticker(cfg.timeouts().tick(), cfg.nanoClock());
            ScheduledExecutorService createdScheduler = null;
            ExecutorService createdCleanup = null;
            try {
                createdScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("rd-close-timer", false));
                createdCleanup = Executors.newCachedThreadPool(daemonFactory("rd-cleanup-", true));
            } catch (RuntimeException failure) {
                createdTicker.close();
                if (createdScheduler != null) createdScheduler.shutdownNow();
                if (createdCleanup != null) createdCleanup.shutdownNow();
                throw failure;
            }
            this.ticker = createdTicker;
            this.scheduler = createdScheduler;
            this.cleanup = createdCleanup;
        }
    }

    public static AppRuntime forHost(AppConfig cfg, AdapterFactory adapters) throws AdapterException {
        return forHost(cfg, adapters, Seams.none());
    }

    static AppRuntime forHost(AppConfig cfg, AdapterFactory adapters, Seams seams) throws AdapterException {
        return new AppRuntime(LaunchOptions.Role.HOST, cfg, Objects.requireNonNull(adapters), seams);
    }

    static AppRuntime forHost(AppConfig cfg, AdapterFactory adapters, Seams seams,
            Runnable afterHostSnapshotProjection, Runnable beforeHostDisconnectClose) throws AdapterException {
        Objects.requireNonNull(afterHostSnapshotProjection);
        Objects.requireNonNull(beforeHostDisconnectClose);
        return new AppRuntime(LaunchOptions.Role.HOST, cfg, Objects.requireNonNull(adapters), seams,
                afterHostSnapshotProjection, beforeHostDisconnectClose);
    }

    public static AppRuntime forViewer(AppConfig cfg) { return forViewer(cfg, Seams.none()); }

    static AppRuntime forViewer(AppConfig cfg, Seams seams) {
        try {
            return new AppRuntime(LaunchOptions.Role.VIEWER, cfg, null, seams);
        } catch (AdapterException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public LaunchOptions.Role role() {
        return role;
    }

    // ---- test accessors ------------------------------------------------------------------------

    /** Live ticker registrations: the Client runtime's own ticker or the running HostCore's. */
    int tickerRegistrations() {
        if (role == LaunchOptions.Role.VIEWER) return ticker.registrations();
        HostCore c;
        synchronized (lock) {
            c = core;
        }
        return c == null ? 0 : c.tickerRegistrations();
    }

    InputExecutor inputExecutor() {
        return inputExecutor;
    }

    Optional<ViewerSession> viewerSession() {
        synchronized (lock) {
            return Optional.ofNullable(slot == null ? null : slot.session);
        }
    }

    HostCore hostCore() {
        synchronized (lock) {
            return core;
        }
    }

    // ---- Server --------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> startHost() {
        if (role != LaunchOptions.Role.HOST) {
            return CompletableFuture.failedFuture(new IllegalStateException("not a server runtime"));
        }
        if (inputFaulted()) return CompletableFuture.failedFuture(new IllegalStateException("input unavailable"));
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
        HostCore c = null;
        try {
            c = new HostCore(new HostConfig(cfg.bindAddress(), cfg.cmdPort(), cfg.imgPort(), cfg.timeouts(),
                    cfg.nanoClock(), new SecureRandom(), seams.imageOutputDecorator()), inputExecutor, adapters,
                    this::publishBounds, seams.beforeSenderStart(), this::observeSession);
            boolean proceed;
            synchronized (lock) {
                proceed = !shuttingDown.get();
                if (proceed) core = c;
            }
            if (!proceed) throw new IllegalStateException("shutting down");
            seams.beforeHostStart().run();
            if (shuttingDown.get()) throw new IllegalStateException("shutting down");
            c.start();
            synchronized (lock) {
                serverState = ServerState.STARTED;
            }
            seams.afterHostStart().run();
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
        HostCore c = startedCore();
        return !inputFaulted() && c != null && c.sessions().regenerateCode();
    }

    /** The running core, or null when this is not a started Server or it is shutting down. */
    private HostCore startedCore() {
        if (role != LaunchOptions.Role.HOST || shuttingDown.get()) return null;
        synchronized (lock) {
            return serverState == ServerState.STARTED ? core : null;
        }
    }

    @Override
    public SendResult sendInput(long generation, long inputEpoch, Packet inputPacket) {
        if (role != LaunchOptions.Role.VIEWER) return SendResult.WRONG_ROLE;
        if (shuttingDown.get()) return SendResult.SHUTTING_DOWN;
        ViewerSession session = viewerSession().orElse(null);
        if (session == null) return SendResult.NO_SESSION;
        if (session.generation() != generation) return SendResult.STALE_GENERATION;
        return switch (session.offerInput(inputEpoch, inputPacket)) {
            case SENT -> SendResult.SENT;
            case GATE_CLOSED -> SendResult.GATE_CLOSED;
            case STALE_EPOCH -> SendResult.STALE_EPOCH;
            case OVERFLOW -> SendResult.OVERFLOW;
        };
    }

    @Override
    public boolean releaseControlInput(long generation) {
        if (role != LaunchOptions.Role.VIEWER || shuttingDown.get()) return false;
        ViewerSession session = viewerSession().orElse(null);
        return session != null && session.generation() == generation
                && session.releaseControlInput() == CommandWriter.OfferResult.ACCEPTED;
    }

    // ---- Client --------------------------------------------------------------------------------

    @Override
    public ConnectOutcome connect(String host, String code) {
        if (role != LaunchOptions.Role.VIEWER) return ConnectOutcome.WRONG_ROLE;
        if (shuttingDown.get()) return ConnectOutcome.SHUTTING_DOWN;
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.isEmpty() || code == null || !CODE.matcher(code).matches()) return ConnectOutcome.INVALID_INPUT;
        while (true) {
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
        ViewerSession cs = v.session();
        if (cs == null || !cs.lifecycle().isClosed()) return ConnectOutcome.BUSY;
        if (!(cs.socketsClosed().isDone() && cs.terminated().isDone())) return ConnectOutcome.BUSY;
        return null;
    }

    private boolean inputFaulted() {
        return lastInputFault != null || (inputExecutor != null && inputExecutor.state() == InputExecutor.State.FAULTED);
    }

    private void prepareAndStart(ClientSlot s, String host, String code) {
        try {
            if (abandoned(s)) {
                endCancelled(s);
                return;
            }
            ViewerConfig clientCfg = new ViewerConfig(host, cfg.cmdPort(), cfg.imgPort(), code, cfg.timeouts(), cfg.nanoClock());
            ViewerSession session = new ViewerSession(clientCfg, s.generation, seams.connector(), seams.hooks(),
                    ticker, scheduler, cleanup);
            boolean start;
            synchronized (lock) {
                start = !s.cancelled && !shuttingDown.get();
                if (start) s.session = session;
            }
            if (start) session.start();
            else {
                session.cancel();
                endCancelled(s);
            }
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "connect startup failed: " + e.getClass().getSimpleName());
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
        if (role != LaunchOptions.Role.VIEWER) return false;
        ViewerSession cs;
        synchronized (lock) {
            if (slot == null || slot.ended) return false;
            if (slot.session == null) { // still queued: rd-app will see the flag and create nothing
                if (slot.cancelled) return false;
                slot.cancelled = true;
                return true;
            }
            cs = slot.session;
        }
        // each closeIf decides and closes in one transaction, so an ACTIVE session is never closed here;
        // the phase only moves forward, so a session that left CONNECTING is caught by a later check
        CloseInfo info = new CloseInfo(Packet.DisconnectReason.USER, "cancelled", true);
        var lifecycle = cs.lifecycle();
        return lifecycle.closeIf(ViewerPhase.CONNECTING, info) || lifecycle.closeIf(ViewerPhase.AUTHENTICATING, info)
                || lifecycle.closeIf(ViewerPhase.ATTACHING, info);
    }

    // ---- both roles ----------------------------------------------------------------------------

    @Override
    public boolean disconnect() {
        if (shuttingDown.get()) return false;
        if (role == LaunchOptions.Role.HOST) {
            HostCore c = startedCore();
            HostSession session = c == null ? null : c.sessions().current().orElse(null);
            if (session == null || session.closeInfo().isPresent()) return false;
            beforeHostDisconnectClose.run(); // test seam outside both runtime and session locks
            return session.close(new CloseInfo(Packet.DisconnectReason.USER, "disconnected", true));
        }
        ViewerSession cs;
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
        return role == LaunchOptions.Role.HOST ? serverSnapshot() : clientSnapshot();
    }

    /** A snapshot without a session: the configured ports, nothing else. */
    private Snapshot blank(UiPhase phase, String message) {
        return new Snapshot(role, phase, 0, 0, message, null, cfg.cmdPort(), cfg.imgPort(), null, 0, 0, null, 0,
                false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
    }

    private Snapshot serverSnapshot() {
        HostCore c;
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
        // code and session are read until the session did not change around the code read: the manager
        // issues a new code only while no session exists, so a code read with a session absent on both
        // sides belongs to the LISTENING state shown
        String code;
        HostSession session;
        while (true) {
            HostSession before = c.sessions().current().orElse(null);
            code = c.sessions().currentCode();
            session = c.sessions().current().orElse(null);
            if (before == session) break;
        }
        if (session == null) {
            return new Snapshot(role, UiPhase.LISTENING, 0, 0, message, null, cmdPort, imgPort, code, 0, 0, null, 0,
                    false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
        }
        Optional<CloseInfo> closed = session.closeInfo();
        UiPhase phase = closed.isPresent() || session.phase() == HostPhase.CLOSING ? UiPhase.CLOSING
                : session.phase() == HostPhase.ACTIVE ? UiPhase.ACTIVE : UiPhase.RESERVED;
        String shownMessage = closed.map(i -> Messages.forClose(i.reason())).orElse("");
        Snapshot.Cleanup pending = Snapshot.Cleanup.NONE;
        if (closed.isPresent()) {
            if (faultClosedSession == session) pending = Snapshot.Cleanup.INPUT_FAULTED;
            else if (session.inputReleased().isCompletedExceptionally()) pending = Snapshot.Cleanup.INPUT_FAILED;
            else if (!session.inputReleased().isDone()) pending = Snapshot.Cleanup.INPUT_PENDING;
            else if (!session.terminated().isDone()) pending = Snapshot.Cleanup.WORKERS;
        }
        if (pending == Snapshot.Cleanup.INPUT_FAILED) shownMessage = Messages.INPUT_RELEASE_FAILED;
        else if (pending == Snapshot.Cleanup.INPUT_FAULTED) shownMessage = Messages.INPUT_FAULTED;
        else if (session.preparationFailure().isPresent()) shownMessage = Messages.forThrowable(session.preparationFailure().orElseThrow());
        afterHostSnapshotProjection.run(); // test seam outside both runtime and session locks
        Optional<Packet.SizePayload> screen = session.screen();
        return new Snapshot(role, phase, session.generation(), 0, shownMessage,
                session.peer().getHostAddress(), cmdPort, imgPort, null,
                screen.map(Packet.SizePayload::width).orElse(0), screen.map(Packet.SizePayload::height).orElse(0),
                null, session.framesSent(), false, stats(session.traffic(), session.rttNanos()), pending);
    }

    /**
     * Reservation clears the previous message; only this close callback records a terminal reason.
     * Snapshots cannot overwrite it with a projection captured before close. An older session's
     * callback is ignored once a newer reservation has been observed.
     */
    private void observeSession(HostSession session) {
        long generation = session.generation();
        synchronized (lock) {
            if (generation <= observedGeneration) return;
            observedSession = session;
            observedGeneration = generation;
            serverMessage = "";
        }
        session.socketsClosed().thenAccept(info -> {
            String normalized = Messages.forClose(info.reason());
            Optional<Throwable> failure = session.preparationFailure();
            if (info.reason() == Packet.DisconnectReason.PROTOCOL_ERROR
                    && info.message().equals("host screen unavailable") && failure.isPresent()) {
                normalized = Messages.forThrowable(failure.orElseThrow());
            }
            synchronized (lock) {
                if (observedSession == session && observedGeneration == generation && serverState == ServerState.STARTED) {
                    serverMessage = normalized;
                }
            }
        });
    }

    private Snapshot clientSnapshot() {
        SlotView v;
        synchronized (lock) { v = slot == null ? null : slot.view(); }
        if (v == null || v.ended()) return blank(UiPhase.IDLE, v == null ? "" : v.failure());
        ViewerSession cs = v.session();
        if (cs == null) return new Snapshot(role, UiPhase.CONNECTING, v.generation(), 0, "", v.host(),
                cfg.cmdPort(), cfg.imgPort(), null, 0, 0, null, 0, false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
        ControlView view = cs.controlView();
        boolean closed = view.phase() == ViewerPhase.CLOSING;
        if (closed && cs.socketsClosed().isDone() && cs.terminated().isDone()) return blank(UiPhase.IDLE, closeMessage(cs));
        UiPhase phase = switch (view.phase()) {
            case CONNECTING -> UiPhase.CONNECTING;
            case AUTHENTICATING -> UiPhase.AUTHENTICATING;
            case ATTACHING -> UiPhase.ATTACHING;
            case ACTIVE -> UiPhase.ACTIVE;
            case CLOSING -> UiPhase.CLOSING;
        };
        Optional<Packet.SizePayload> screen = cs.screen();
        return new Snapshot(role, phase, view.generation(), view.inputEpoch(), closed ? closeMessage(cs) : "",
                v.host(), cfg.cmdPort(), cfg.imgPort(), null,
                screen.map(Packet.SizePayload::width).orElse(0), screen.map(Packet.SizePayload::height).orElse(0),
                view.frame(), view.framesReceived(), view.controlEnabled(), stats(cs.traffic(), cs.rttNanos()), Snapshot.Cleanup.NONE);
    }

    private static String closeMessage(ViewerSession cs) {
        Optional<Packet.AuthStatus> auth = cs.authFailure();
        if (auth.isPresent()) return Messages.forAuth(auth.get());
        return cs.lifecycle().closeInfo().map(i -> Messages.forClose(i.reason())).orElse("");
    }

    private static TrafficStats stats(TrafficSnapshot t, OptionalLong rtt) {
        return new TrafficStats(t.cmdIn(), t.cmdOut(), t.imgIn(), t.imgOut(), rtt.orElse(TrafficStats.NO_RTT));
    }

    // ---- input executor callbacks ------------------------------------------------------------------

    private void publishBounds(ScreenGeometry geometry) { boundsSnapshot = geometry; }

    private Rectangle currentBounds() {
        ScreenGeometry geometry = boundsSnapshot;
        return geometry == null ? new Rectangle() : geometry.bounds();
    }

    private void onInputFault(Throwable error) {
        lastInputFault = error;
        HostCore c;
        synchronized (lock) { c = core; }
        HostSession session = c == null ? null : c.sessions().current().orElse(null);
        if (session == null || session.closeInfo().isPresent()) return;
        synchronized (lock) {
            if (core != c) return;
            faultClosedSession = session;
        }
        if (!session.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "input fault", true))) {
            synchronized (lock) {
                if (faultClosedSession == session) faultClosedSession = null;
            }
        }
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
            boolean appStopped = false;
            try {
                appStopped = app.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!appStopped) app.shutdownNow(); // interrupt a task stuck in an adapter or a bind
            workersStopped = appStopped; // rd-app counts as a worker, for both roles
            if (role == LaunchOptions.Role.HOST) {
                HostCore c;
                synchronized (lock) {
                    c = core;
                }
                if (c != null) {
                    // a session reserved while close() runs is only visible afterwards, so look on both sides
                    HostSession before = c.sessions().current().orElse(null);
                    c.closeUntil(deadline);
                    HostSession after = c.sessions().current().orElse(null);
                    for (HostSession session : new HostSession[] {before, after == before ? null : after}) {
                        if (session == null) continue;
                        socketsClosed &= session.socketsClosed().isDone();
                        await(session.socketsClosed(), deadline);
                        await(session.terminated(), deadline);
                        await(session.inputReleased(), deadline);
                        socketsClosed &= session.socketsClosed().isDone();
                        workersStopped &= session.terminated().isDone();
                        if (session.inputBeginCount() > 0) {
                            CompletableFuture<Void> released = session.inputReleased();
                            release = released.isCompletedExceptionally() ? InputRelease.FAILED
                                    : released.isDone() ? InputRelease.RELEASED : InputRelease.UNCONFIRMED;
                        }
                    }
                }
            } else {
                ViewerSession cs;
                synchronized (lock) {
                    cs = slot == null ? null : slot.session;
                }
                if (cs != null) {
                    cs.lifecycle().close(new CloseInfo(Packet.DisconnectReason.SHUTDOWN, "app shutting down", true));
                    await(cs.socketsClosed(), deadline);
                    await(cs.terminated(), deadline);
                    socketsClosed = cs.socketsClosed().isDone();
                    workersStopped &= cs.terminated().isDone();
                }
                ticker.close();
                scheduler.shutdownNow();
                cleanup.shutdownNow();
            }
            if (inputExecutor != null) {
                CompletableFuture<Void> executorStopped = inputExecutor.shutdownAsync();
                await(executorStopped, deadline);
                workersStopped &= executorStopped.isDone() && !executorStopped.isCompletedExceptionally();
                if (executorStopped.isCompletedExceptionally()) release = InputRelease.FAILED;
                else if (!executorStopped.isDone() && release == InputRelease.RELEASED) release = InputRelease.UNCONFIRMED;
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
