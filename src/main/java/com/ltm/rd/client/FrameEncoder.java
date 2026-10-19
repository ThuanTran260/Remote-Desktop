package com.ltm.rd.client;

import com.ltm.rd.common.Protocol;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Scales a screen capture down to at most {@code maxWidth} and compresses it to JPEG. */
public final class FrameEncoder {
    public record EncodedFrame(int width, int height, byte[] jpeg) {}

    private final int maxWidth;
    private final float quality;

    public FrameEncoder(int maxWidth, float quality) {
        if (maxWidth < 1) throw new IllegalArgumentException("maxWidth must be positive");
        if (quality < 0f || quality > 1f) throw new IllegalArgumentException("quality must be in [0, 1]");
        this.maxWidth = maxWidth;
        this.quality = quality;
    }

    public EncodedFrame encode(BufferedImage src) throws IOException {
        int w = Math.min(src.getWidth(), maxWidth);
        int h = Math.max(1, (int) Math.round((double) w * src.getHeight() / src.getWidth()));

        // Always draw into TYPE_INT_RGB: the JPEG writer rejects images with an alpha channel.
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            byte[] jpeg = out.toByteArray();
            if (jpeg.length > Protocol.IMG_MAX_LEN - 8) throw new IOException("frame too large");
            return new EncodedFrame(w, h, jpeg);
        } finally {
            writer.dispose();
        }
    }
}
