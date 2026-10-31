package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.FakeClock;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link HandshakeHandler} driven directly on the test thread over real loopback sockets. Whatever
 * the peer wrote is already in the socket buffer when {@code handle} runs, so no case waits on a
 * timer; the outcome is read from the session manager, the session's close info and what the peer
 * receives. Failures after the reservation are forced through the {@code onReserved} callback and
 * the clock the handler reads, which are its only collaborators that run mid-handshake.
 */
@Timeout(60)
class HandshakeHandlerTest {
    private static final int WAIT_MS = 10_000;
    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(2), Duration.ofSeconds(8),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(3),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));

    private final FakeClock clock = new FakeClock();
    private final List<Socket> sockets = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService cleanup;
    private ServerSocket listener;
    private SessionManager sessions;
    private PendingConnections pending;

    @BeforeEach
    void setUp() throws IOException {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-sched");
            t.setDaemon(true);
            return t;
        });
        cleanup = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-cleanup");
            t.setDaemon(true);
            return t;
        });
        listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(WAIT_MS);
        sessions = new SessionManager(new SecureRandom(), new AuthGuard(16, 3, Duration.ofSeconds(30)), TIMEOUTS,
                clock, this::newSession);
        pending = new PendingConnections(TIMEOUTS.handshake());
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Socket s : sockets) s.close();
        listener.close();
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private ServerSession newSession(long generation, byte[] token, InetAddress peer, long reservedAt) {
        SessionLifecycle<ServerPhase> lifecycle = new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        ServerSession.Env env = new ServerSession.Env(TIMEOUTS, clock, null, () -> false, null, false);
        return new ServerSession(generation, token, peer, reservedAt, lifecycle, env);
    }

    private HandshakeHandler handler(LongSupplier handlerClock, Consumer<ServerSession> onReserved) {
        return new HandshakeHandler(sessions, pending, TIMEOUTS, handlerClock, onReserved, null);
    }

    /** One accepted connection: {@code client} is the peer's end, {@code pc} the server's. */
    private record Link(Socket client, PendingConnection pc) {
        void send(Packet p) throws IOException {
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            PacketIO.write(out, p);
            out.flush();
        }

        /** The next packet the server sent, or empty when it closed the connection. */
        Optional<Packet> receive() throws IOException {
            return PacketIO.read(new DataInputStream(client.getInputStream()), Protocol.CMD_MAX_LEN,
                    EnumSet.allOf(PacketType.class));
        }
    }

    private Link link() throws IOException {
        Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        client.setSoTimeout(WAIT_MS);
        sockets.add(client);
        Socket accepted = listener.accept();
        sockets.add(accepted);
        return new Link(client, new PendingConnection(accepted, clock.getAsLong()));
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the test only wants the socket gone
        }
    }

    // ---- end of stream before the first packet -------------------------------------------------

    @Test
    void endOfStreamBeforeAuthEndsTheHandshakeWithoutReserving() throws Exception {
        String codeBefore = sessions.currentCode();
        Link link = link();
        link.client().shutdownOutput(); // the peer leaves without sending AUTH

        handler(clock, s -> fail("no session may be reserved")).handle(HandshakeHandler.Kind.COMMAND, link.pc());

        assertTrue(sessions.current().isEmpty());
        assertEquals(codeBefore, sessions.currentCode());
        assertTrue(link.receive().isEmpty(), "the server closed the connection without a reply");
    }

    @Test
    void endOfStreamBeforeAttachClosesOnlyThatConnection() throws Exception {
        Link link = link();
        link.client().shutdownOutput(); // the peer leaves without sending ATTACH

        handler(clock, s -> fail("nothing to reserve on the image port")).handle(HandshakeHandler.Kind.IMAGE,
                link.pc());

        assertTrue(sessions.current().isEmpty());
        assertTrue(link.receive().isEmpty(), "the server closed the connection");
    }

    @Test
    void attachWithoutASessionClosesTheConnection() throws Exception {
        String codeBefore = sessions.currentCode();
        Link link = link();
        link.send(Packet.attach(new byte[Protocol.TOKEN_LEN])); // no AUTH happened, there is nothing to attach to

        handler(clock, s -> fail("ATTACH never reserves")).handle(HandshakeHandler.Kind.IMAGE, link.pc());

        assertTrue(sessions.current().isEmpty());
        assertEquals(codeBefore, sessions.currentCode());
        assertTrue(link.receive().isEmpty(), "no reply: the connection is simply closed");
    }

    // ---- failures after the reservation --------------------------------------------------------

    @Test
    void handOverRefusedAfterTheReplyClosesTheReservedSession() throws Exception {
        Link link = link();
        link.send(Packet.auth(sessions.currentCode()));
        // the reply goes out, but by the time the session would take the socket the handshake window is over
        Consumer<ServerSession> pastTheDeadline = s -> clock.advance(TIMEOUTS.handshake());

        handler(clock, pastTheDeadline).handle(HandshakeHandler.Kind.COMMAND, link.pc());

        ServerSession session = sessions.current().orElseThrow();
        CloseInfo info = session.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("command channel not attached", info.message());
        assertFalse(info.sendDisconnect());
        assertEquals(ServerPhase.CLOSING, session.phase());
        Packet reply = link.receive().orElseThrow();
        assertEquals(PacketType.AUTH_RESULT, reply.type());
        assertEquals(Packet.AuthStatus.OK, reply.authResultPayload().status());
        assertTrue(link.receive().isEmpty(), "the unclaimed socket is closed by the handler");
    }

    @Test
    void writeFailureAfterTheReservationClosesTheSession() throws Exception {
        Link link = link();
        link.send(Packet.auth(sessions.currentCode()));
        // the socket dies between the reservation and the reply
        Consumer<ServerSession> socketDies = s -> closeQuietly(link.pc().socket());

        handler(clock, socketDies).handle(HandshakeHandler.Kind.COMMAND, link.pc());

        ServerSession session = sessions.current().orElseThrow();
        CloseInfo info = session.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("handshake failed", info.message());
        assertFalse(info.sendDisconnect());
        assertTrue(link.receive().isEmpty(), "AUTH_RESULT never left the server");
    }

    @Test
    void writeFailureBeforeAnySessionEndsOnlyThatConnection() throws Exception {
        String codeBefore = sessions.currentCode();
        Link link = link();
        link.send(Packet.auth(codeBefore.equals("000000") ? "000001" : "000000")); // a wrong code: no session
        AtomicBoolean armed = new AtomicBoolean(true);
        // the first time the handler reads its clock is while it prepares the WRONG_CODE reply
        LongSupplier socketDiesOnFirstRead = () -> {
            if (armed.compareAndSet(true, false)) closeQuietly(link.pc().socket());
            return clock.getAsLong();
        };

        handler(socketDiesOnFirstRead, s -> fail("a wrong code never reserves")).handle(HandshakeHandler.Kind.COMMAND,
                link.pc());

        assertFalse(armed.get(), "the failure was injected");
        assertTrue(sessions.current().isEmpty(), "there was no session to close");
        assertEquals(codeBefore, sessions.currentCode());
        assertTrue(link.receive().isEmpty(), "no reply was written");
    }
}
