package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Fixed-geometry screen source producing blank images; no AWT Robot involved. */
public final class FakeScreenSource implements ScreenSource {
    private final ScreenGeometry geometry;

    public FakeScreenSource(int width, int height) {
        this.geometry = new ScreenGeometry(new Rectangle(0, 0, width, height), 1.0, 1.0);
    }

    @Override
    public ScreenGeometry geometry() {
        return geometry;
    }

    @Override
    public BufferedImage capture(Rectangle bounds) {
        return new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height), BufferedImage.TYPE_INT_RGB);
    }
}
