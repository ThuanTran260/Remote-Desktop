package com.ltm.rd.viewer;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketRules;
import com.ltm.rd.common.PacketRules.Lane;
import com.ltm.rd.common.PacketRules.Shell;
import com.ltm.rd.common.PacketRules.Stage;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.ProtocolException;
import com.ltm.rd.common.SessionPort;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Body of the {@code rd-frame-receiver} worker: reads FRAME packets from the image channel,
 * decodes them outside every lock and publishes them to the session. Any decode failure, even an
 * unexpected RuntimeException, is a protocol error. Image content is never logged.
 */
public final class FrameReceiver implements Runnable {
    private final SessionPort port;
    private final ControlSide target;
    private final Channel img;
    private final FrameDecoder decoder;
    private final int screenWidth;
    private final int screenHeight;
    private final LongSupplier clock;

    public FrameReceiver(SessionPort port, ControlSide target, Channel img, FrameDecoder decoder, int screenWidth,
            int screenHeight, LongSupplier clock) {
        this.port = Objects.requireNonNull(port);
        this.target = Objects.requireNonNull(target);
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
                Optional<Packet> p = PacketIO.read(img.in(), Protocol.IMG_MAX_LEN,
                        PacketRules.allowedIn(Shell.LISTENER, Lane.IMAGE, Stage.ACTIVE));
                if (p.isEmpty()) {
                    close(Packet.DisconnectReason.USER, "peer closed image channel", false);
                    return;
                }
                BufferedImage image = decoder.decode(p.get().framePayload(), screenWidth, screenHeight);
                target.publishFrame(port.generation(), image, clock.getAsLong());
                if (port.isClosed()) return;
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
        port.close(new CloseInfo(reason, message, sendDisconnect));
    }
}
