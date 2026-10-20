package com.ltm.rd.server;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.ProtocolException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Body of the {@code rd-frame-receiver} worker: reads FRAME packets from the image channel,
 * decodes them outside every lock and publishes them to the session. Any decode failure, even an
 * unexpected RuntimeException, is a protocol error. Image content is never logged.
 */
final class FrameReceiver implements Runnable {
    private static final Set<PacketType> FRAME_ONLY = Set.of(PacketType.FRAME);

    private final ServerSession session;
    private final Channel img;
    private final FrameDecoder decoder;
    private final int screenWidth;
    private final int screenHeight;
    private final LongSupplier clock;

    FrameReceiver(ServerSession session, Channel img, FrameDecoder decoder, int screenWidth, int screenHeight,
            LongSupplier clock) {
        this.session = Objects.requireNonNull(session);
        this.img = Objects.requireNonNull(img);
        this.decoder = Objects.requireNonNull(decoder);
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Optional<Packet> p = PacketIO.read(img.in(), Protocol.IMG_MAX_LEN, FRAME_ONLY);
                if (p.isEmpty()) {
                    close(Packet.DisconnectReason.USER, "peer closed image channel", false);
                    return;
                }
                BufferedImage image = decoder.decode(p.get().framePayload(), screenWidth, screenHeight);
                session.publishFrame(session.generation(), image, clock.getAsLong());
                if (session.lifecycle().isClosed()) return;
            }
        } catch (SocketTimeoutException e) {
            close(Packet.DisconnectReason.TIMEOUT, "image read timeout", true);
        } catch (IOException e) {
            close(Packet.DisconnectReason.PROTOCOL_ERROR, "image channel lost", false);
        } catch (ProtocolException e) {
            close(Packet.DisconnectReason.PROTOCOL_ERROR, "invalid frame", true);
        } catch (RuntimeException e) { // a decoder bug or a hostile JPEG must not escape as anything but this
            close(Packet.DisconnectReason.PROTOCOL_ERROR, "invalid frame", true);
        }
    }

    private void close(Packet.DisconnectReason reason, String message, boolean sendDisconnect) {
        session.lifecycle().close(new CloseInfo(reason, message, sendDisconnect));
    }
}
