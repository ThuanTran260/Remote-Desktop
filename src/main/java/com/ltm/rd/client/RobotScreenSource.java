package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/** {@link ScreenSource} on a {@link RobotPort}; only the {@code rd-screen-sender} thread captures. */
public final class RobotScreenSource implements ScreenSource {
    private final RobotPort port;
    private final ScreenGeometry geometry;

    public RobotScreenSource(RobotPort port, DisplayPort display) {
        this.port = Objects.requireNonNull(port);
        this.geometry = new ScreenGeometry(display.bounds(), display.scaleX(), display.scaleY());
    }

    @Override
    public ScreenGeometry geometry() {
        return geometry;
    }

    @Override
    public BufferedImage capture(Rectangle bounds) throws IOException {
        try {
            return port.createScreenCapture(bounds);
        } catch (RuntimeException e) {
            throw new IOException("screen capture failed", e);
        }
    }
}
