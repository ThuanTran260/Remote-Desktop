package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.FrameEncoder;
import com.ltm.rd.common.Channel;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.SessionLifecycle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** FrameReceiver run synchronously over canned bytes: how each kind of bad input ends the session. */
class FrameReceiverTest {
    private static final byte[] TOKEN = new byte[16];

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

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private ServerSession session() {
        SessionLifecycle<ServerPhase> lifecycle = new SessionLifecycle<>(ServerPhase.RESERVED, ServerPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(50), scheduler, cleanup);
        return new ServerSession(1, TOKEN, InetAddress.getLoopbackAddress(), 0, lifecycle);
    }

    private static Channel channelOver(byte[] bytes) {
        return new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(bytes)),
                new DataOutputStream(OutputStream.nullOutputStream()));
    }

    private static byte[] wire(Packet... packets) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        for (Packet p : packets) PacketIO.write(data, p);
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        return new FrameEncoder(8192, 0.6f).encode(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)).jpeg();
    }

    private static CloseInfo run(ServerSession session, byte[] bytes, FrameDecoder decoder) {
        new FrameReceiver(session, channelOver(bytes), decoder, 1920, 1080, System::nanoTime).run();
        return session.lifecycle().closeInfo().orElseThrow();
    }

    @Test
    void declaredSizeMismatchIsProtocolErrorWithoutFullDecode() throws Exception {
        DelegatingImageReader spy = new DelegatingImageReader();
        byte[] small = jpeg(640, 360);
        CloseInfo info = run(session(), wire(Packet.frame(1280, 720, small)), new FrameDecoder(() -> spy));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("invalid frame", info.message());
        assertEquals(0, spy.readCalls.get(), "the pixels of a mislabelled frame must never be decoded");
        assertEquals(1, spy.disposeCalls.get());
    }

    @Test
    void garbageJpegIsProtocolError() throws Exception {
        CloseInfo info = run(session(), wire(Packet.frame(1280, 720, new byte[200])), new FrameDecoder());
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("invalid frame", info.message());
    }

    @Test
    void wrongRatioHeaderIsProtocolErrorBeforeAReaderIsCreated() throws Exception {
        boolean[] readerCreated = new boolean[1];
        FrameDecoder decoder = new FrameDecoder(() -> {
            readerCreated[0] = true;
            return new DelegatingImageReader();
        });
        CloseInfo info = run(session(), wire(Packet.frame(1280, 1024, new byte[10])), decoder);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertFalse(readerCreated[0]);
    }

    @Test
    void runtimeExceptionFromTheDecoderIsProtocolError() throws Exception {
        FrameDecoder decoder = new FrameDecoder(() -> {
            throw new IllegalStateException("decoder bug");
        });
        CloseInfo info = run(session(), wire(Packet.frame(1280, 720, jpeg(1280, 720))), decoder);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("invalid frame", info.message());
    }

    @Test
    void endOfStreamClosesWithItsOwnCause() {
        CloseInfo info = run(session(), new byte[0], new FrameDecoder());
        assertEquals(Packet.DisconnectReason.USER, info.reason());
        assertEquals("peer closed image channel", info.message());
    }

    @Test
    void truncatedFrameIsAConnectionLossNotAProtocolError() throws Exception {
        byte[] whole = wire(Packet.frame(1280, 720, jpeg(1280, 720)));
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);
        CloseInfo info = run(session(), cut, new FrameDecoder());
        assertEquals("image channel lost", info.message());
    }
}
