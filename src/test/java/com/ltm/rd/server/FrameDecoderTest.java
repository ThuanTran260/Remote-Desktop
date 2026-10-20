package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.FrameEncoder;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.ProtocolException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FrameDecoderTest {
    private static byte[] jpeg(int w, int h) throws IOException {
        return new FrameEncoder(8192, 0.6f).encode(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)).jpeg();
    }

    @Test
    void corruptJpegIsProtocolErrorAndReaderDisposed() {
        DelegatingImageReader spy = new DelegatingImageReader();
        FrameDecoder decoder = new FrameDecoder(() -> spy);
        byte[] garbage = new byte[64];
        assertThrows(ProtocolException.class,
                () -> decoder.decode(new Packet.FramePayload(1280, 720, garbage), 1920, 1080));
        assertEquals(1, spy.disposeCalls.get());
        assertEquals(0, spy.readCalls.get());
    }

    @Test
    void declaredSizeMismatchNeverCallsFullRead() throws IOException {
        DelegatingImageReader spy = new DelegatingImageReader();
        FrameDecoder decoder = new FrameDecoder(() -> spy);
        byte[] small = jpeg(640, 360);
        assertThrows(ProtocolException.class,
                () -> decoder.decode(new Packet.FramePayload(1280, 720, small), 1920, 1080));
        assertEquals(0, spy.readCalls.get());
        assertEquals(1, spy.disposeCalls.get());
    }

    @Test
    void matchingSizeIsDecodedOnceAndDisposed() throws IOException {
        DelegatingImageReader spy = new DelegatingImageReader();
        BufferedImage img = new FrameDecoder(() -> spy)
                .decode(new Packet.FramePayload(640, 360, jpeg(640, 360)), 1920, 1080);
        assertEquals(640, img.getWidth());
        assertEquals(1, spy.readCalls.get());
        assertEquals(1, spy.disposeCalls.get());
    }

    @Test
    void headerLargerThanScreenRejectedBeforeReaderCreated() {
        AtomicInteger created = new AtomicInteger();
        FrameDecoder decoder = new FrameDecoder(() -> {
            created.incrementAndGet();
            return new DelegatingImageReader();
        });
        assertThrows(ProtocolException.class,
                () -> decoder.decode(new Packet.FramePayload(1281, 721, new byte[16]), 1280, 720));
        assertEquals(0, created.get());
    }

    @Test
    void wrongRatioRejected() {
        AtomicInteger created = new AtomicInteger();
        FrameDecoder decoder = new FrameDecoder(() -> {
            created.incrementAndGet();
            return new DelegatingImageReader();
        });
        assertThrows(ProtocolException.class,
                () -> decoder.decode(new Packet.FramePayload(1280, 1024, new byte[16]), 1920, 1080));
        assertEquals(0, created.get());
    }
}
