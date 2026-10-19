package com.ltm.rd.server;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
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
import java.util.function.LongSupplier;

/**
 * Listens on the command and image ports, runs handshakes on a bounded pool and owns the shared
 * ticker, close scheduler and cleanup executor. The session factory only constructs; the ticker
 * registration and the socketsClosed wiring happen after {@code tryReserve} returned.
 */
public final class ServerCore implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ServerCore.class.getName());
    private static final int POOL_THREADS = 4;
    private static final int POOL_QUEUE = 8;
    private static final long CLOSE_WAIT_MS = 5000;

    /** Hooks for tests; {@link #none()} in production. Either value may be null. */
    public record Seams(ThreadFactory lifecycleThreads, Runnable afterTokenCheck) {
        public static Seams none() {
            return new Seams(null, null);
        }
    }

    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final ServerConfig config;
    private final Seams seams;
    private final PendingConnections pending;
    private final SessionManager sessions;
    private final HandshakeHandler handler;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocket cmdSocket;
    private volatile ServerSocket imgSocket;
    private volatile Ticker ticker;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService cleanup;
    private volatile ThreadPoolExecutor pool;
    private volatile Thread acceptCmd;
    private volatile Thread acceptImg;

    public ServerCore(ServerConfig config) {
        this(config, Seams.none());
    }

    public ServerCore(ServerConfig config, Seams seams) {
        this.config = Objects.requireNonNull(config);
        this.seams = Objects.requireNonNull(seams);
        this.timeouts = config.timeouts();
        this.clock = config.nanoClock();
        this.pending = new PendingConnections(timeouts.handshake());
        this.sessions = new SessionManager(config.random(),
                new AuthGuard(1024, 3, timeouts.ipLock()), timeouts, clock, this::createSession);
        this.handler = new HandshakeHandler(sessions, pending, timeouts, clock, this::onReserved);
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
        cmdSocket = cmd;
        imgSocket = img;
        scheduler = Executors.newSingleThreadScheduledExecutor(factory("rd-close-timer", false));
        cleanup = Executors.newCachedThreadPool(factory("rd-cleanup-", true));
        Ticker t = new Ticker(timeouts.tick(), clock);
        ticker = t;
        t.register(pending);
        pool = new ThreadPoolExecutor(POOL_THREADS, POOL_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(POOL_QUEUE), factory("rd-handshake-", true), rejectionHandler());
        acceptCmd = startAcceptor("rd-accept-cmd", cmd, HandshakeHandler.Kind.COMMAND);
        acceptImg = startAcceptor("rd-accept-img", img, HandshakeHandler.Kind.IMAGE);
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
                socket.setTcpNoDelay(true);
                pool.execute(new HandshakeTask(kind, pc)); // a full pool closes the socket in the handler
            } catch (Throwable e) {
                pc.closeIfPending();
                pending.remove(pc);
            }
        }
    }

    /** Builds a session that is not running yet; called under the manager lock. */
    private ServerSession createSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos) {
        SessionLifecycle<ServerPhase> lifecycle = seams.lifecycleThreads() == null
                ? new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING, clock, timeouts.closeGrace(),
                        scheduler, cleanup)
                : new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING, clock, timeouts.closeGrace(),
                        scheduler, cleanup, seams.lifecycleThreads());
        ServerSession.Env env = new ServerSession.Env(timeouts, clock, ticker, sessions::shuttingDown,
                seams.afterTokenCheck());
        return new ServerSession(generation, token, peer, reservedAtNanos, lifecycle, env);
    }

    /** After tryReserve returned: start the reserve deadline and free the slot once sockets are closed. */
    private void onReserved(ServerSession session) {
        Ticker.Registration registration = ticker.register(session);
        session.lifecycle().addStopHook(registration::close);
        session.lifecycle().socketsClosed().thenRun(() -> sessions.onSessionSocketsClosed(session));
    }

    /**
     * Shuts down: no new sessions, ports closed, the session closed, pending handshakes dropped,
     * then the executors stopped. Bounded to about five seconds in total.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS);
        sessions.setShuttingDown();
        closeQuietly(cmdSocket);
        closeQuietly(imgSocket);

        ServerSession session = sessions.current().orElse(null); // manager lock released before the session's
        if (session != null) {
            session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.SHUTDOWN, "server shutting down", true));
        }
        pending.closeAll();
        ThreadPoolExecutor p = pool;
        if (p != null) {
            for (Runnable r : p.shutdownNow()) {
                if (r instanceof HandshakeTask t) t.abandon();
            }
            awaitQuietly(() -> p.awaitTermination(remainingMs(deadline, 1000), TimeUnit.MILLISECONDS));
        }
        join(acceptCmd, deadline);
        join(acceptImg, deadline);
        if (session != null) {
            awaitQuietly(() -> session.lifecycle().terminated().get(remainingMs(deadline, 3000), TimeUnit.MILLISECONDS));
        }
        pending.closeAll();
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
