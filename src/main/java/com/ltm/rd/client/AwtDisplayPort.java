package com.ltm.rd.client;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/** Reads the primary screen of the default {@link GraphicsEnvironment} once, at construction. */
public final class AwtDisplayPort implements DisplayPort {
    private final Rectangle bounds;
    private final double scaleX;
    private final double scaleY;

    /** @throws java.awt.HeadlessException when there is no graphics environment */
    public AwtDisplayPort() {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        bounds = gc.getBounds();
        scaleX = gc.getDefaultTransform().getScaleX();
        scaleY = gc.getDefaultTransform().getScaleY();
    }

    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    @Override
    public double scaleX() {
        return scaleX;
    }

    @Override
    public double scaleY() {
        return scaleY;
    }
}
