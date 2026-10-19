package com.ltm.rd.client;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.ProtocolException;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Validation;
import java.awt.Rectangle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns input packets from the Server into {@link InputCommand}s. It runs inside the session
 * transaction, so the phase check and the submit are one atomic decision;
 * {@code InputExecutor.submit} and {@code CommandWriter.offer} are the allowed non-blocking calls.
 */
public final class CommandReceiver {
    private final InputExecutor input;
    private final long generation;
    private final Rectangle bounds;
    private final CommandWriter writer;
    private final AtomicLong releaseAllReceived = new AtomicLong();

    public CommandReceiver(InputExecutor input, long generation, Rectangle bounds, CommandWriter writer) {
        this.input = Objects.requireNonNull(input);
        this.generation = generation;
        this.bounds = new Rectangle(Objects.requireNonNull(bounds));
        this.writer = Objects.requireNonNull(writer);
    }

    /** RELEASE_ALL packets accepted at the protocol level (not proof that the device released). */
    public long releaseAllReceived() {
        return releaseAllReceived.get();
    }

    /** Handles PING and the input packets; other types are the session's business. */
    public void handle(SessionLifecycle.Tx<ClientPhase> tx, Packet p) {
        if (p.type() == PacketType.PING) {
            if (writer.offer(Packet.pong(p.seq())) == CommandWriter.OfferResult.OVERFLOW) {
                tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "command queue overflow", true));
            }
            return;
        }
        if (tx.isClosed()) return;
        if (tx.phase() != ClientPhase.ACTIVE) {
            tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "input before session active", true));
            return;
        }
        InputCommand cmd;
        try {
            cmd = toCommand(p);
        } catch (ProtocolException e) {
            tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "invalid input command", true));
            return;
        }
        if (cmd == null) {
            tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "unexpected " + p.type(), true));
            return;
        }
        switch (input.submit(generation, cmd)) {
            case ACCEPTED -> {
                if (cmd instanceof InputCommand.ReleaseAll) releaseAllReceived.incrementAndGet();
            }
            case OVERFLOW -> tx.close(new CloseInfo(Packet.DisconnectReason.OVERFLOW, "input queue overflow", true));
            case STALE -> tx.close(new CloseInfo(Packet.DisconnectReason.PROTOCOL_ERROR, "stale input session", true));
        }
    }

    /** Null for a packet type that is not an input command. */
    private InputCommand toCommand(Packet p) {
        return switch (p.type()) {
            case MOUSE_MOVE -> {
                Packet.PointerPayload m = p.pointerPayload();
                Validation.checkPointer(m.x(), m.y(), bounds.width, bounds.height);
                yield new InputCommand.MouseMove(m.x(), m.y());
            }
            case MOUSE_PRESS -> {
                Packet.PointerPayload m = p.pointerPayload();
                Validation.checkPointer(m.x(), m.y(), bounds.width, bounds.height);
                yield new InputCommand.MousePress(m.x(), m.y(), m.button());
            }
            case MOUSE_RELEASE -> {
                Packet.PointerPayload m = p.pointerPayload();
                Validation.checkPointer(m.x(), m.y(), bounds.width, bounds.height);
                yield new InputCommand.MouseRelease(m.x(), m.y(), m.button());
            }
            case WHEEL -> new InputCommand.Wheel(p.wheelNotches());
            case KEY_PRESS -> new InputCommand.KeyPress(p.keyCode());
            case KEY_RELEASE -> new InputCommand.KeyRelease(p.keyCode());
            case RELEASE_ALL -> new InputCommand.ReleaseAll();
            default -> null;
        };
    }
}
