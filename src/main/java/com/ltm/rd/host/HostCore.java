package com.ltm.rd.host;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.AdapterFactory;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.host.ScreenGeometry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Listens on the command and image ports, runs handshakes on a bounded pool and owns the shared
 * ticker, close scheduler and cleanup executor. The session factory only constructs; the ticker
 * registration and the release wiring happen after {@code tryReserve} returned.
 */
public final class HostCore implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(HostCore.class.getName());
    private static final int POOL_THREADS = 4;
    private static final int POOL_QUEUE = 8;
    private static final long CLOSE_WAIT_MS = 5000;

    /**
     * Hooks for tests in this package; {@link #none()} in production. The runnables may be null.
     * {@code afterTokenCheck} runs inside the session lock, {@code beforeAttachImage} outside any
     * lock, {@code afterBind} between the bind and the publication of the sockets, {@code beforeResources}
     * after the sockets are published and before the executors, ticker, pool and acceptors are created;
     * {@code pendingTickDisabled} leaves pending connections to their soTimeout fallback;
     * {@code inputReleaseSeam} transforms the side's closing source, never the session's target.
     */
    record Seams(ThreadFactory lifecycleThreads, Runnable afterTokenCheck, Runnable beforeAttachImage,
            boolean pendingTickDisabled, Runnable afterBind, Runnable beforeResources,
            UnaryOperator<CompletableFuture<Void>> inputReleaseSeam) {
        Seams(ThreadFactory lifecycleThreads, Runnable afterTokenCheck, Runnable beforeAttachImage,
                boolean pendingTickDisabled, Runnable afterBind, Runnable beforeResources) {
            this(lifecycleThreads, afterTokenCheck, beforeAttachImage, pendingTickDisabled, afterBind,
                    beforeResources, null);
        }

        Seams(ThreadFactory lifecycleThreads, Runnable afterTokenCheck, Runnable beforeAttachImage,
                boolean pendingTickDisabled, Runnable afterBind) {
            this(lifecycleThreads, afterTokenCheck, beforeAttachImage, pendingTickDisabled, afterBind, null);
        }

        Seams(ThreadFactory lifecycleThreads, Runnable afterTokenCheck, Runnable beforeAttachImage,
                boolean pendingTickDisabled) {
            this(lifecycleThreads, afterTokenCheck, beforeAttachImage, pendingTickDisabled, null, null);
        }

        static Seams none() {
            return new Seams(null, null, null, false, null, null);
        }
    }

    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final HostConfig config;
    private final Seams seams;
    private final InputExecutor input;
    private final AdapterFactory adapters;
    private final Consumer<ScreenGeometry> publishBounds;
    private final Runnable beforeSenderStart;
    private final Consumer<HostSession> sessionObserver;
    private final PendingConnections pending;
    private final SessionManager sessions;
    private final HandshakeHandler handler;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocket cmdSocket;
    private volatile ServerSocket imgSocket;
    private volatile Ticker ticker;
    private volatile Ticker.Registration pendingRegistration;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService cleanup;
    private volatile ThreadPoolExecutor pool;
    private volatile Thread acceptCmd;
    private volatile Thread acceptImg;

    public HostCore(HostConfig config, InputExecutor input, AdapterFactory adapters,
            Consumer<ScreenGeometry> publishBounds, Runnable beforeSenderStart, Consumer<HostSession> sessionObserver) {
        this(config, input, adapters, publishBounds, beforeSenderStart, sessionObserver, Seams.none());
    }

    HostCore(HostConfig config, InputExecutor input, AdapterFactory adapters,
            Consumer<ScreenGeometry> publishBounds, Runnable beforeSenderStart,
            Consumer<HostSession> sessionObserver, Seams seams) {
        this.config = Objects.requireNonNull(config);
        this.seams = Objects.requireNonNull(seams);
        this.input = Objects.requireNonNull(input);
        this.adapters = Objects.requireNonNull(adapters);
        this.publishBounds = Objects.requireNonNull(publishBounds);
        this.beforeSenderStart = Objects.requireNonNull(beforeSenderStart);
        this.sessionObserver = Objects.requireNonNull(sessionObserver);
        this.timeouts = config.timeouts();
        this.clock = config.nanoClock();
        this.pending = new PendingConnections(timeouts.handshake());
        this.sessions = new SessionManager(config.random(),
                new AuthGuard(1024, 3, timeouts.ipLock()), timeouts, clock, this::createSession);
        this.handler = new HandshakeHandler(sessions, pending, timeouts, clock, this::onReserved,
                seams.beforeAttachImage(), config.imageOutputDecorator());
    }

    public SessionManager sessions() {
        return sessions;
    }

    public int cmdPort() {
        return cmdSocket.getLocalPort();
    }

    public int imgPort() {
        return imgSocket.getLocalPort();
    }

    /** Number of live ticker registrations, for leak checks in tests. */
    public int tickerRegistrations() {
        Ticker t = ticker;
        return t == null ? 0 : t.registrations();
    }

    /** Binds both ports (if one fails the other is released) and starts accepting. */
    public void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("server is closed");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("already started");
        ServerSocket cmd = null;
        ServerSocket img = null;
        try {
            cmd = bind(config.cmdPort());
            img = bind(config.imgPort());
        } catch (IOException | RuntimeException e) {
            closeQuietly(cmd);
            closeQuietly(img);
            throw e;
        }
        if (seams.afterBind() != null) seams.afterBind().run(); // test seam: a close() racing the bind
        cmdSocket = cmd;
        imgSocket = img;
        if (closed.get()) { // close() ran before the sockets were published: it could not close them
            closeQuietly(cmd);
            closeQuietly(img);
            throw new IllegalStateException("server is closed");
        }
        if (seams.beforeResources() != null) seams.beforeResources().run(); // test seam: a close() racing the setup
        scheduler = Executors.newSingleThreadScheduledExecutor(factory("rd-close-timer", false));
        cleanup = Executors.newCachedThreadPool(factory("rd-cleanup-", true));
        Ticker t = new Ticker(timeouts.tick(), clock);
        ticker = t;
        if (!seams.pendingTickDisabled()) pendingRegistration = t.register(pending);
        pool = new ThreadPoolExecutor(POOL_THREADS, POOL_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(POOL_QUEUE), factory("rd-handshake-", true), rejectionHandler());
        acceptCmd = startAcceptor("rd-accept-cmd", cmd, HandshakeHandler.Kind.COMMAND);
        acceptImg = startAcceptor("rd-accept-img", img, HandshakeHandler.Kind.IMAGE);
        // close() sets 'closed' before it reads the fields above. If it read one of them before this method
        // assigned it, 'closed' is already true here (both are volatile accesses), so this check catches every
        // resource close() could have missed; if 'closed' is still false, close() has not read the fields yet
        // and will see all of them.
        if (closed.get()) {
            closeQuietly(cmd);
            closeQuietly(img);
            releaseResources(null, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS));
            throw new IllegalStateException("server is closed");
        }
    }

    private ServerSocket bind(int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        try {
            ss.bind(new InetSocketAddress(config.bindAddress(), port), 50);
        } catch (IOException | RuntimeException e) {
            closeQuietly(ss);
            throw e;
        }
        return ss;
    }

    private static ThreadFactory factory(String prefix, boolean numbered) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, numbered ? prefix + seq.incrementAndGet() : prefix);
            t.setDaemon(true);
            return t;
        };
    }

    /** One accepted connection waiting in the pool; a rejected or drained task closes its socket. */
    private final class HandshakeTask implements Runnable {
        private final HandshakeHandler.Kind kind;
        private final PendingConnection connection;

        HandshakeTask(HandshakeHandler.Kind kind, PendingConnection connection) {
            this.kind = kind;
            this.connection = connection;
        }

        @Override
        public void run() {
            handler.handle(kind, connection);
        }

        void abandon() {
            connection.closeIfPending();
            pending.remove(connection);
        }
    }

    private RejectedExecutionHandler rejectionHandler() {
        return (task, executor) -> {
            if (task instanceof HandshakeTask t) t.abandon();
        };
    }

    private Thread startAcceptor(String name, ServerSocket server, HandshakeHandler.Kind kind) {
        Thread t = new Thread(() -> acceptLoop(server, kind), name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void acceptLoop(ServerSocket server, HandshakeHandler.Kind kind) {
        while (!closed.get() && !server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (closed.get() || server.isClosed()) return;
                LOG.log(System.Logger.Level.WARNING, "accept failed: " + e.getClass().getSimpleName());
                LockSupport.parkNanos(10_000_000L); // do not spin on a persistent error
                continue;
            }
            PendingConnection pc = new PendingConnection(socket, clock.getAsLong());
            pending.add(pc);
            try {
                configureAcceptedSocket(socket, kind, (int) timeouts.handshake().toMillis());
                pool.execute(new HandshakeTask(kind, pc)); // a full pool closes the socket in the handler
            } catch (Throwable e) {
                pc.closeIfPending();
                pending.remove(pc);
            }
        }
    }

    static void configureAcceptedSocket(Socket socket, HandshakeHandler.Kind kind, int handshakeTimeoutMillis)
            throws SocketException {
        socket.setTcpNoDelay(true);
        if (kind == HandshakeHandler.Kind.IMAGE) socket.setSendBufferSize(64 * 1024);
        socket.setSoTimeout(handshakeTimeoutMillis); // fallback if the sweep is late
    }

    /** Builds a session that is not running yet; called under the manager lock. */
    private HostSession createSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos) {
        SessionLifecycle<HostPhase> lifecycle = seams.lifecycleThreads() == null
                ? new SessionLifecycle<>(HostPhase.RESERVED, HostPhase.CLOSING, clock, timeouts.closeGrace(),
                        scheduler, cleanup)
                : new SessionLifecycle<>(HostPhase.RESERVED, HostPhase.CLOSING, clock, timeouts.closeGrace(),
                        scheduler, cleanup, seams.lifecycleThreads());
        HostSession.Env env = new HostSession.Env(timeouts, clock, ticker, sessions::shuttingDown,
                seams.afterTokenCheck(), seams.inputReleaseSeam(), input, adapters, publishBounds, beforeSenderStart);
        return new HostSession(generation, token, peer, reservedAtNanos, lifecycle, env);
    }

    /** After tryReserve returned: start the deadline and release only after all closing work succeeds. */
    private void onReserved(HostSession session) {
        // wired first: if anything below failed, the slot would otherwise never be freed
        CompletableFuture.allOf(session.socketsClosed(), session.terminated(), session.inputReleased())
                .thenRun(() -> sessions.onSessionReleased(session)); // failed release keeps the slot BUSY
        Ticker.Registration registration = ticker.register(session);
        session.lifecycle().addStopHook(registration::close);
        sessionObserver.accept(session);
    }

    /**
     * Shuts down: no new sessions, ports closed, the session closed, pending handshakes dropped,
     * then the executors stopped. Bounded to about five seconds in total.
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS);
        closeUntil(deadline);
    }

    public void closeUntil(long deadlineNanos) {
        if (!closed.compareAndSet(false, true)) return;
        sessions.setShuttingDown();
        closeQuietly(cmdSocket);
        closeQuietly(imgSocket);

        HostSession session = sessions.current().orElse(null); // manager lock released before the session's
        if (session != null) {
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.SHUTDOWN, "server shutting down", true));
        }
        releaseResources(session, deadlineNanos);
    }

    /**
     * Stops the acceptors, the pool, the ticker and the executors, and waits (bounded by {@code deadline})
     * for the given session to terminate. Called by {@link #close()} and by a {@link #start()} that found
     * itself overtaken by it, so it is idempotent and may run on both threads at once. It takes no lock.
     */
    private void releaseResources(HostSession session, long deadline) {
        // acceptors first: nothing can be added to the pool or the pending set after they are gone
        join(acceptCmd, deadline);
        join(acceptImg, deadline);
        pending.closeAll();
        ThreadPoolExecutor p = pool;
        if (p != null) {
            for (Runnable r : p.shutdownNow()) {
                if (r instanceof HandshakeTask t) t.abandon();
            }
            awaitQuietly(() -> p.awaitTermination(remainingMs(deadline, 1000), TimeUnit.MILLISECONDS));
        }
        if (session != null) {
            awaitQuietly(() -> session.lifecycle().terminated().get(remainingMs(deadline, 3000), TimeUnit.MILLISECONDS));
        }
        pending.closeAll();
        Ticker.Registration registration = pendingRegistration;
        if (registration != null) registration.close();
        Ticker t = ticker;
        if (t != null) t.close();
        ExecutorService c = cleanup;
        if (c != null) c.shutdownNow();
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
    }

    private static long remainingMs(long deadlineNanos, long capMs) {
        return Math.max(1, Math.min(capMs, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
    }

    private interface Waiter {
        void await() throws Exception;
    }

    private static void awaitQuietly(Waiter waiter) {
        try {
            waiter.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "shutdown wait ended: " + e.getClass().getSimpleName());
        }
    }

    private static void join(Thread t, long deadlineNanos) {
        if (t == null) return;
        try {
            t.join(remainingMs(deadlineNanos, 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }
}
