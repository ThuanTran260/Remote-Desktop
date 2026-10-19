package com.ltm.rd.client;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Source of the shared screen; the real one uses the AWT Robot, tests use a fake. */
public interface ScreenSource {
    ScreenGeometry geometry();

    BufferedImage capture(Rectangle bounds) throws IOException;
}
