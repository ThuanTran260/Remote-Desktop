package com.ltm.rd.it;

import com.ltm.rd.viewer.ViewerSession;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.host.HostCore;
import com.ltm.rd.host.HostSession;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test-only TCP relay between a real {@link ViewerSession} and a real {@link HostCore}. It
 * decodes every packet in both directions, records one line per packet per channel, and forwards
 * it unchanged (except for the opt-in ATTACH token tamper). Recording happens before forwarding,
 * so packets that are causally related are always recorded in causal order.
 *
 * <p>A line reads {@code <direction> <packet> @<phase>}. The phase is the phase of the side that
 * sent the packet, sampled just before the packet is forwarded: the client session's phase for
 * {@code C>S}, the phase of the server session this connection reserved for {@code S>C} ({@code -}
 * when the connection never owned one, e.g. WRONG_CODE or BUSY). The next transition of either side
 * needs the packet to be delivered first, so the sample is deterministic.
 *
 * <p>Consecutive FRAME packets of one direction collapse into one line, because how many frames fit
 * before a test stops is scheduling. All relay threads are daemons named {@code test-relay-*}.
 */
final class RecordingRelay implements AutoCloseable {
    private static final int MAX_WAIT_S = 10;

    enum Dir {
        C2S("C>S"),
        S2C("S>C");

        final String arrow;

        Dir(String arrow) {
            this.arrow = arrow;
        }
    }

    enum Lane { CMD, IMG }

    /** The recorded packets of one channel, in the order the relay saw them. */
    static final class Trace {
        private record Entry(Dir dir, PacketType type, String text) {}

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final List<Entry> entries = new ArrayList<>();
        private boolean frozen;

        void add(Dir dir, Packet packet, String phase) {
            lock.lock();
            try {
                if (frozen) return;
                Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
                if (packet.type() == PacketType.FRAME && last != null && last.dir == dir
                        && last.type == PacketType.FRAME) {
                    return; // collapsed: the frame count is scheduling, not protocol
                }
                entries.add(new Entry(dir, packet.type(), dir.arrow + " " + describe(packet) + " @" + phase));
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Stops recording; whatever the test tears down afterwards is not part of the trace. */
        void freeze() {
            lock.lock();
            try {
                frozen = true;
            } finally {
                lock.unlock();
            }
        }

        /** Every recorded line, in order. */
        List<String> lines() {
            lock.lock();
            try {
                List<String> out = new ArrayList<>();
                for (Entry e : entries) out.add(e.text);
                return out;
            } finally {
                lock.unlock();
            }
        }

        /** Like {@link #lines()} without PING and PONG, the only packets allowed to float. */
        List<String> linesWithoutHeartbeat() {
            lock.lock();
            try {
                List<String> out = new ArrayList<>();
                for (Entry e : entries) {
                    if (e.type != PacketType.PING && e.type != PacketType.PONG) out.add(e.text);
                }
                return out;
            } finally {
                lock.unlock();
            }
        }

        /** Index of the first line of {@code type} in {@code dir}, or -1. */
        int indexOf(Dir dir, PacketType type) {
            lock.lock();
            try {
                for (int i = 0; i < entries.size(); i++) {
                    Entry e = entries.get(i);
                    if (e.dir == dir && e.type == type) return i;
                }
                return -1;
            } finally {
                lock.unlock();
            }
        }

        /** Index of the first PING or PONG line in either direction, or -1. */
        int indexOfHeartbeat() {
            lock.lock();
            try {
                for (int i = 0; i < entries.size(); i++) {
                    PacketType t = entries.get(i).type;
                    if (t == PacketType.PING || t == PacketType.PONG) return i;
                }
                return -1;
            } finally {
                lock.unlock();
            }
        }

        /** Waits (bounded) until a packet of {@code type} was recorded in {@code dir}. */
        boolean await(Dir dir, PacketType type) throws InterruptedException {
            long nanos = Duration.ofSeconds(MAX_WAIT_S).toNanos();
            lock.lock();
            try {
                while (indexOfLocked(dir, type) < 0) {
                    if (nanos <= 0) return false;
                    nanos = changed.awaitNanos(nanos);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        private int indexOfLocked(Dir dir, PacketType type) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.dir == dir && e.type == type) return i;
            }
            return -1;
        }
    }

    /** Names the packet and the fields that carry protocol meaning; codes and tokens are never printed. */
    static String describe(Packet p) {
        return switch (p.type()) {
            case AUTH -> "AUTH(v" + p.authPayload().version() + ")";
            case AUTH_RESULT -> "AUTH_RESULT(" + p.authResultPayload().status() + ")";
            case ATTACH -> "ATTACH(v" + p.attachPayload().version() + ")";
            case SCREEN_INFO -> {
                Packet.SizePayload s = p.sizePayload();
                yield "SCREEN_INFO(" + s.width() + "x" + s.height() + ")";
            }
            case DISCONNECT -> {
                Packet.DisconnectPayload d = p.disconnectPayload();
                yield "DISCONNECT(" + d.reason() + ", \"" + d.message() + "\")";
            }
            default -> p.type().name();
        };
    }

    private final HostCore core;
    private final AtomicReference<ViewerSession> client = new AtomicReference<>();
    private final AtomicReference<HostSession> serverSession = new AtomicReference<>();
    private final Trace cmdTrace = new Trace();
    private final Trace imgTrace = new Trace();
    private final CountDownLatch imageGate = new CountDownLatch(1);
    private final CountDownLatch imageClosedByServer = new CountDownLatch(1);
    private final boolean tamperAttachToken;
    private final ServerSocket cmdListener;
    private final ServerSocket imgListener;
    private final List<Socket> sockets = new ArrayList<>();
    private final List<Socket> imageSockets = new ArrayList<>();
    private volatile CountDownLatch authResultGate;
    private volatile CountDownLatch attachForwardGate;
    private volatile ServerEofGate serverEofGate;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean closed;
    private boolean tampered; // only touched by the single IMG C2S pump

    /**
     * @param holdImage when true the relay does not accept image connections until
     *     {@link #openImageGate()}; the client's connect still succeeds (backlog), nothing reaches the server
     * @param tamperAttachToken when true the first ATTACH is forwarded with a corrupted token
     */
    RecordingRelay(HostCore core, boolean holdImage, boolean tamperAttachToken) throws IOException {
        this.core = core;
        this.tamperAttachToken = tamperAttachToken;
        this.cmdListener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.imgListener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        if (!holdImage) imageGate.countDown();
        startThread("test-relay-accept-cmd", () -> acceptLoop(Lane.CMD, cmdListener, core.cmdPort()));
        startThread("test-relay-accept-img", () -> acceptLoop(Lane.IMG, imgListener, core.imgPort()));
    }

    int cmdPort() {
        return cmdListener.getLocalPort();
    }

    int imgPort() {
        return imgListener.getLocalPort();
    }

    Trace cmd() {
        return cmdTrace;
    }

    Trace img() {
        return imgTrace;
    }

    /** The client whose phase is sampled for {@code C>S} lines; set once the session exists. */
    void bind(ViewerSession session) {
        client.set(session);
    }

    void openImageGate() {
        imageGate.countDown();
    }

    void holdAuthResult() { authResultGate = new CountDownLatch(1); }
    void holdAttachForward() { attachForwardGate = new CountDownLatch(1); }

    /** Holds one server EOF until the test has observed the other channel's close decision. */
    ServerEofGate holdServerEof(Lane lane) {
        if (serverEofGate != null) throw new IllegalStateException("server EOF gate already installed");
        serverEofGate = new ServerEofGate(lane);
        return serverEofGate;
    }

    final class ServerEofGate {
        private final Lane heldLane;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private ServerEofGate(Lane heldLane) { this.heldLane = heldLane; }

        void awaitHeld() throws InterruptedException {
            if (!reached.await(MAX_WAIT_S, TimeUnit.SECONDS)) {
                throw new AssertionError("server EOF did not reach the gate");
            }
            checkFailure();
        }

        void release() { release.countDown(); }

        private void beforeForward(Lane lane) {
            try {
                if (lane == heldLane) {
                    reached.countDown();
                    if (!release.await(MAX_WAIT_S, TimeUnit.SECONDS)) {
                        failure.compareAndSet(null, new AssertionError("server EOF gate release timed out"));
                    }
                } else if (!reached.await(MAX_WAIT_S, TimeUnit.SECONDS)) {
                    failure.compareAndSet(null, new AssertionError("held server EOF was not observed"));
                }
            } catch (InterruptedException e) {
                if (!closed) failure.compareAndSet(null, e);
                Thread.currentThread().interrupt();
            }
        }

        private void checkFailure() {
            Throwable cause = failure.get();
            if (cause != null) throw new AssertionError("server EOF forwarding fixture failed", cause);
        }
    }
    void openAttachForwardGate() {
        CountDownLatch gate = attachForwardGate;
        if (gate != null) gate.countDown();
    }
    synchronized String imageState() {
        return "sockets=" + imageSockets.size() + ", allClosed=" + imageSockets.stream().allMatch(Socket::isClosed)
                + ", imageGate=" + imageGate.getCount() + ", attachGate="
                + (attachForwardGate == null ? "none" : attachForwardGate.getCount());
    }
    void openAuthResultGate() {
        CountDownLatch gate = authResultGate;
        if (gate != null) gate.countDown();
    }

    void closeImageConnections() {
        List<Socket> copy;
        synchronized (this) { copy = new ArrayList<>(imageSockets); }
        copy.forEach(RecordingRelay::closeQuietly);
    }

    /** Waits (bounded) until the real server closed the image connection this relay opened. */
    boolean awaitImageClosedByServer() throws InterruptedException {
        return imageClosedByServer.await(MAX_WAIT_S, TimeUnit.SECONDS);
    }

    void freeze() {
        cmdTrace.freeze();
        imgTrace.freeze();
    }

    private void acceptLoop(Lane lane, ServerSocket listener, int serverPort) {
        try {
            if (lane == Lane.IMG) imageGate.await(); // connections wait in the backlog meanwhile
            while (!closed) {
                Socket clientSide = listener.accept();
                Socket serverSide = new Socket(InetAddress.getLoopbackAddress(), serverPort);
                track(clientSide);
                track(serverSide);
                if (lane == Lane.IMG) {
                    synchronized (this) { imageSockets.add(clientSide); imageSockets.add(serverSide); }
                }
                AtomicInteger open = new AtomicInteger(2);
                startThread("test-relay-" + lane + "-c2s",
                        () -> pump(lane, Dir.C2S, clientSide, serverSide, open));
                startThread("test-relay-" + lane + "-s2c",
                        () -> pump(lane, Dir.S2C, serverSide, clientSide, open));
            }
        } catch (IOException | InterruptedException e) {
            // closed by the test, or the server is gone: nothing more to relay
        }
    }

    private void pump(Lane lane, Dir dir, Socket from, Socket to, AtomicInteger open) {
        Trace trace = lane == Lane.CMD ? cmdTrace : imgTrace;
        int maxLen = lane == Lane.CMD ? Protocol.CMD_MAX_LEN : Protocol.IMG_MAX_LEN;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(from.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(to.getOutputStream()));
            while (true) {
                Optional<Packet> read = PacketIO.read(in, maxLen, EnumSet.allOf(PacketType.class));
                if (read.isEmpty()) break;
                Packet packet = read.get();
                record(trace, dir, packet);
                CountDownLatch attachGate = attachForwardGate;
                if (attachGate != null && lane == Lane.IMG && dir == Dir.C2S && packet.type() == PacketType.ATTACH) {
                    try { attachGate.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                CountDownLatch gate = authResultGate;
                if (gate != null && lane == Lane.CMD && dir == Dir.S2C && packet.type() == PacketType.AUTH_RESULT) {
                    try { gate.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                PacketIO.write(out, maybeTamper(lane, dir, packet));
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            // the peer vanished or sent garbage: this direction is over
        } finally {
            ServerEofGate gate = serverEofGate;
            if (gate != null && dir == Dir.S2C) gate.beforeForward(lane);
            if (dir == Dir.S2C && lane == Lane.IMG) imageClosedByServer.countDown();
            shutdownOutput(to); // pass the EOF on without discarding what is still in flight the other way
            if (open.decrementAndGet() == 0) {
                closeQuietly(from);
                closeQuietly(to);
            }
        }
    }

    private void record(Trace trace, Dir dir, Packet packet) {
        if (dir == Dir.S2C && packet.type() == PacketType.AUTH_RESULT
                && packet.authResultPayload().status() == Packet.AuthStatus.OK) {
            serverSession.set(core.sessions().current().orElse(null)); // the session this connection reserved
        }
        trace.add(dir, packet, phase(dir));
    }

    private String phase(Dir dir) {
        if (dir == Dir.C2S) {
            ViewerSession c = client.get();
            return c == null ? "-" : c.phase().name();
        }
        HostSession s = serverSession.get();
        return s == null ? "-" : s.phase().name();
    }

    private Packet maybeTamper(Lane lane, Dir dir, Packet packet) {
        if (!tamperAttachToken || tampered || lane != Lane.IMG || dir != Dir.C2S
                || packet.type() != PacketType.ATTACH) {
            return packet;
        }
        tampered = true;
        Packet.AttachPayload a = packet.attachPayload();
        byte[] token = a.token().clone();
        token[0] ^= 0x7F;
        return Packet.attach(a.version(), token);
    }

    private synchronized void track(Socket s) {
        sockets.add(s);
    }

    private synchronized void startThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    private static void shutdownOutput(Socket s) {
        try {
            s.shutdownOutput();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // test teardown
        }
    }

    @Override
    public void close() {
        closed = true;
        ServerEofGate eofGate = serverEofGate;
        if (eofGate != null) eofGate.release();
        openAuthResultGate();
        openAttachForwardGate();
        imageGate.countDown();
        List<Socket> toClose;
        List<Thread> toJoin;
        synchronized (this) {
            toClose = new ArrayList<>(sockets);
            toJoin = new ArrayList<>(threads);
        }
        try {
            cmdListener.close();
            imgListener.close();
        } catch (IOException ignored) {
            // test teardown
        }
        toClose.forEach(RecordingRelay::closeQuietly);
        for (Thread t : toJoin) {
            t.interrupt(); // releases an accept loop still waiting on the image gate
            try {
                t.join(MAX_WAIT_S * 1000L);
                if (eofGate != null && t.isAlive()) {
                    throw new AssertionError("server EOF relay worker did not terminate: " + t.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (eofGate != null) throw new AssertionError("interrupted joining server EOF relay", e);
                return;
            }
        }
        if (eofGate != null) eofGate.checkFailure();
    }
}
