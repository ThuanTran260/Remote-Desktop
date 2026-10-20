package com.ltm.rd.client;

import java.awt.Rectangle;
import java.util.Objects;

/** Bounds of the shared screen in logical pixels plus the scale to physical pixels. */
public record ScreenGeometry(Rectangle bounds, double scaleX, double scaleY) {
    public ScreenGeometry {
        Objects.requireNonNull(bounds);
        bounds = new Rectangle(bounds);
    }

    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }
}
