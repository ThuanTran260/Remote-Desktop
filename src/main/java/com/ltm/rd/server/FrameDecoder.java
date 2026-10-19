package com.ltm.rd.server;

import com.ltm.rd.common.Packet;
import com.ltm.rd.common.ProtocolException;
import com.ltm.rd.common.Validation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Validates a FRAME payload and decodes its JPEG. The header is checked against the screen size,
 * and the JPEG's own dimensions must equal the header, before the full image is decoded.
 */
public final class FrameDecoder {
    private final Supplier<ImageReader> readers;

    public FrameDecoder() {
        this(FrameDecoder::jpegReader);
    }

    /** Seam for tests that need to observe the reader. */
    FrameDecoder(Supplier<ImageReader> readers) {
        this.readers = readers;
    }

    public BufferedImage decode(Packet.FramePayload p, int rw, int rh) {
        Validation.checkFrameHeader(p.width(), p.height(), rw, rh);
        ImageReader reader = readers.get();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(p.jpeg()))) {
            reader.setInput(in, true, true);
            // Only the JPEG header is parsed here, so a lying FRAME header is caught before decoding.
            int jw = reader.getWidth(0);
            int jh = reader.getHeight(0);
            if (jw != p.width() || jh != p.height()) {
                throw new ProtocolException("jpeg size " + jw + "x" + jh
                        + " does not match frame header " + p.width() + "x" + p.height());
            }
            return reader.read(0);
        } catch (IOException e) {
            throw new ProtocolException("invalid jpeg: " + e.getMessage());
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader jpegReader() {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("jpeg");
        if (!it.hasNext()) throw new IllegalStateException("no jpeg reader available");
        return it.next();
    }
}
