package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Validation;
import com.ltm.rd.server.FrameDecoder;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class FrameEncoderTest {
    private final FrameEncoder encoder = new FrameEncoder(1280, 0.6f);

    private static BufferedImage image(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void downscalesWideImageKeepingRatio() throws IOException {
        FrameEncoder.EncodedFrame f = encoder.encode(image(1920, 1080));
        assertEquals(1280, f.width());
        assertEquals(720, f.height());
    }

    @Test
    void keepsSmallImageSize() throws IOException {
        FrameEncoder.EncodedFrame f = encoder.encode(image(800, 600));
        assertEquals(800, f.width());
        assertEquals(600, f.height());
    }

    @Test
    void extremeRatioHeightAtLeastOne() throws IOException {
        FrameEncoder.EncodedFrame f = encoder.encode(image(8000, 2));
        assertEquals(1280, f.width());
        assertEquals(1, f.height());
    }

    @Test
    void outputPassesValidationRatioRule() throws IOException {
        int[][] screens = {{1366, 768}, {1920, 1200}, {2560, 1080}};
        for (int[] s : screens) {
            FrameEncoder.EncodedFrame f = encoder.encode(image(s[0], s[1]));
            Validation.checkFrameHeader(f.width(), f.height(), s[0], s[1]);
        }
    }

    @Test
    void encodeThenDecodeRoundTrip() throws IOException {
        FrameEncoder.EncodedFrame f = encoder.encode(image(1920, 1080));
        BufferedImage out = new FrameDecoder()
                .decode(new Packet.FramePayload(f.width(), f.height(), f.jpeg()), 1920, 1080);
        assertEquals(1280, out.getWidth());
        assertEquals(720, out.getHeight());
        Color c = new Color(out.getRGB(640, 360));
        assertTrue(c.getBlue() > 200 && c.getRed() < 60 && c.getGreen() < 60, "pixel was " + c);
    }
}
