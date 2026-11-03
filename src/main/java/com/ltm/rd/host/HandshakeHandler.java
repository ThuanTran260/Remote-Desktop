package com.ltm.rd.host;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketRules.Lane;
import com.ltm.rd.common.PacketRules.Shell;
import com.ltm.rd.common.PacketRules.Stage;
import com.ltm.rd.common.PacketRules;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.common.TrafficCounter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Handshake of one accepted socket, run on a pool thread. {@code Channel.wrap} is called once,
 * before the first packet, and that same Channel is handed over to the session. Whatever happens,
 * a socket that was not handed over is closed here, and codes and tokens are never logged.
 */
final class HandshakeHandler {
    private static final System.Logger LOG = System.getLogger(HandshakeHandler.class.getName());

    enum Kind { COMMAND, IMAGE }

    private final SessionManager sessions;
    private final PendingConnections pending;
    private final Timeouts timeouts;
    private final LongSupplier clock;
    private final Consumer<HostSession> onReserved;
    private final Runnable beforeAttachImage; // test seam, may be null
    private final UnaryOperator<OutputStream> imageOutputDecorator;

    /** {@code onReserved} runs right after a successful reservation, before AUTH_RESULT is sent. */
    HandshakeHandler(SessionManager sessions, PendingConnections pending, Timeouts timeouts, LongSupplier clock,
            Consumer<HostSession> onReserved, Runnable beforeAttachImage,
            UnaryOperator<OutputStream> imageOutputDecorator) {
        this.sessions = sessions;
        this.pending = pending;
        this.timeouts = timeouts;
        this.clock = clock;
        this.onReserved = onReserved;
        this.beforeAttachImage = beforeAttachImage;
        this.imageOutputDecorator = imageOutputDecorator;
    }

    void handle(Kind kind, PendingConnection pc) {
        try {
            Channel ch = Channel.wrap(pc.socket(), new TrafficCounter(),
                    kind == Kind.IMAGE ? imageOutputDecorator : UnaryOperator.identity());
            if (kind == Kind.COMMAND) handleCommand(pc, ch);
            else handleImage(pc, ch);
        } catch (Throwable e) { // incl. ProtocolException: only this connection ends
            LOG.log(System.Logger.Level.DEBUG, "handshake ended: " + e.getClass().getSimpleName());
        } finally {
            pc.closeIfPending(); // no-op when a session took the socket over
            pending.remove(pc);
        }
    }

    private void handleCommand(PendingConnection pc, Channel ch) throws IOException {
        Optional<Packet> first = PacketIO.read(ch.in(), Protocol.CMD_MAX_LEN,
                PacketRules.allowedIn(Shell.LISTENER, Lane.COMMAND, Stage.HANDSHAKE));
        if (first.isEmpty()) return;
        Packet.AuthPayload auth = first.get().authPayload();
        InetAddress peer = ((InetSocketAddress) pc.socket().getRemoteSocketAddress()).getAddress();
        ReserveResult result = sessions.tryReserve(peer, auth.version(), auth.code());
        HostSession session = result.session();
        try {
            if (session != null) onReserved.accept(session);
            if (result.status() == Packet.AuthStatus.WRONG_CODE) delayWrongCode(pc);
            byte[] token = session != null ? session.token() : new byte[Protocol.TOKEN_LEN];
            PacketIO.write(ch.out(), Packet.authResult(result.status(), token));
            ch.out().flush();
            if (session != null && !session.attachCommandChannel(pc, ch, clock.getAsLong())) {
                session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                        "command channel not attached", false));
            }
        } catch (Throwable e) { // anything after OK must not leave a reserved session behind
            if (session != null) {
                session.lifecycle().close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR,
                        "handshake failed", false));
            }
            throw e;
        }
    }

    private void handleImage(PendingConnection pc, Channel ch) throws IOException {
        Optional<Packet> first = PacketIO.read(ch.in(), Protocol.CMD_MAX_LEN,
                PacketRules.allowedIn(Shell.LISTENER, Lane.IMAGE, Stage.HANDSHAKE));
        if (first.isEmpty()) return;
        Optional<HostSession> current = sessions.current();
        if (current.isEmpty()) return;
        if (beforeAttachImage != null) beforeAttachImage.run();
        // REJECTED falls through to finally, which closes only this socket
        current.get().attachImage(first.get().attachPayload(), pc, ch, clock.getAsLong());
    }

    /** Slows down guessing, outside every lock and never past the handshake deadline. */
    private void delayWrongCode(PendingConnection pc) {
        long delay = timeouts.wrongCodeDelay().toNanos();
        long remaining = pc.acceptedAtNanos() + timeouts.handshake().toNanos() - clock.getAsLong();
        // leave one tick so AUTH_RESULT is written before the pending-connection sweep closes the socket
        long wait = Math.min(delay, remaining - timeouts.tick().toNanos());
        if (wait <= 0) return;
        try {
            TimeUnit.NANOSECONDS.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
