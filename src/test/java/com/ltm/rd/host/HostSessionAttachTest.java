package com.ltm.rd.host;

import static com.ltm.rd.common.FakeClock.s;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.HostSession.AttachResult;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** attachImage decisions at unit level, with explicit times: every rejection must leave the token usable. */
class HostSessionAttachTest {
    private static final byte[] TOKEN = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(2), Duration.ofSeconds(8),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(3),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "test-sched");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService cleanup = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final com.ltm.rd.common.FakeClock clock = new com.ltm.rd.common.FakeClock();


    private final com.ltm.rd.host.HostTestAccess.HostResources hostResources = new com.ltm.rd.host.HostTestAccess.HostResources();

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
        cleanup.shutdownNow();
        hostResources.close();
    }

    private HostSession session() throws Exception {
        SessionLifecycle<HostPhase> lifecycle = new SessionLifecycle<>(HostPhase.RESERVED, HostPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        HostSession.Env env = hostResources.env(TIMEOUTS, clock, null, shuttingDown::get, null, null);
        return new HostSession(1, TOKEN, InetAddress.getLoopbackAddress(), 0, lifecycle, env);
    }

    private static Channel channel(PendingConnection pc) {
        return new Channel(pc.socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
    }

    private static PendingConnection pending(long acceptedAt) {
        return new PendingConnection(new Socket(), acceptedAt);
    }

    private static AttachResult attach(HostSession session, PendingConnection pc, int version, byte[] token,
            long now) {
        return session.attachImage(Packet.attach(version, token).attachPayload(), pc, channel(pc), now);
    }

    private static AttachResult attach(HostSession session, PendingConnection pc, long now) {
        return attach(session, pc, 2, TOKEN, now);
    }

    /** A rejected connection stays claimable, so the handler closes exactly that socket. */
    private static void assertRejectedAndClaimable(AttachResult result, PendingConnection pc) {
        assertEquals(AttachResult.REJECTED, result);
        assertTrue(pc.claimForClose());
    }

    @Test
    void attachAtTheHandshakeDeadlineIsRejectedAndTheTokenStaysUsable() throws Exception {
        HostSession session = session();
        PendingConnection late = pending(0);
        assertRejectedAndClaimable(attach(session, late, s(3)), late); // now == acceptedAt + deadline
        PendingConnection inTime = pending(s(2));
        assertEquals(AttachResult.ATTACHED, attach(session, inTime, s(3)), "same token, in time");
    }

    @Test
    void attachJustBeforeTheHandshakeDeadlineIsAccepted() throws Exception {
        HostSession session = session();
        assertEquals(AttachResult.ATTACHED, attach(session, pending(0), s(3) - 1));
    }

    @Test
    void secondAttachAfterSuccessIsRejected() throws Exception {
        HostSession session = session();
        assertEquals(AttachResult.ATTACHED, attach(session, pending(0), s(1)));
        PendingConnection second = pending(0);
        assertRejectedAndClaimable(attach(session, second, s(1)), second);
        assertFalse(session.lifecycle().isClosed(), "a duplicate ATTACH closes only its own socket");
    }

    @Test
    void attachOutsideReservedIsRejected() throws Exception {
        HostSession session = session();
        session.lifecycle().atomically(tx -> tx.advance(HostPhase.RESERVED, HostPhase.ACTIVE));
        PendingConnection pc = pending(0);
        assertRejectedAndClaimable(attach(session, pc, s(1)), pc);
        assertEquals(HostPhase.ACTIVE, session.phase());
    }

    @Test
    void attachAfterTheReserveWindowIsRejectedButBeforeItIsAccepted() throws Exception {
        HostSession session = session();
        PendingConnection expired = pending(s(4));
        clock.set(s(5));
        assertRejectedAndClaimable(attach(session, expired, s(5)), expired); // now == reservedAt + reserve
        assertEquals(Packet.DisconnectReason.TIMEOUT, session.closeInfo().orElseThrow().reason());
        assertEquals(HostPhase.CLOSING, session.phase());
        clock.set(s(5) - 1);
        HostSession fresh = session();
        PendingConnection inTime = pending(s(4));
        assertEquals(AttachResult.ATTACHED, attach(fresh, inTime, s(5) - 1), "a fresh session accepts just before its deadline");
    }

    @Test
    void badVersionWrongTokenAndShutdownAreRejectedWithoutConsumingTheToken() throws Exception {
        HostSession session = session();
        PendingConnection badVersion = pending(0);
        assertRejectedAndClaimable(attach(session, badVersion, 1, TOKEN, s(1)), badVersion);
        byte[] other = TOKEN.clone();
        other[0] ^= 0x7F;
        PendingConnection wrongToken = pending(0);
        assertRejectedAndClaimable(attach(session, wrongToken, 2, other, s(1)), wrongToken);
        shuttingDown.set(true);
        PendingConnection shutdown = pending(0);
        assertRejectedAndClaimable(attach(session, shutdown, s(1)), shutdown);
        shuttingDown.set(false);
        assertEquals(AttachResult.ATTACHED, attach(session, pending(0), s(1)));
    }

    @Test
    void attachToAClosedSessionIsRejected() throws Exception {
        HostSession session = session();
        session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.USER, "closed", false));
        PendingConnection pc = pending(0);
        assertRejectedAndClaimable(attach(session, pc, s(1)), pc);
    }
}
