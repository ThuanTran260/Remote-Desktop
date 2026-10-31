package com.ltm.rd.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

/**
 * Paints a {@link RemotePanel} into an offscreen image, the way Swing would once the panel is
 * showing. The input gate opens only on such a paint, so tests that need an open gate paint first.
 * Headless-safe: it needs no window, only the EDT.
 */
final class PaintSupport {
    private PaintSupport() {}

    /** Paints the panel at its current size; call from any thread, the paint itself runs on the EDT. */
    static BufferedImage paintOnEdt(RemotePanel panel) throws Exception {
        BufferedImage[] out = new BufferedImage[1];
        if (SwingUtilities.isEventDispatchThread()) {
            out[0] = paintNow(panel);
        } else {
            SwingUtilities.invokeAndWait(() -> out[0] = paintNow(panel));
        }
        return out[0];
    }

    /** For code already on the EDT. A zero-size panel paints into a 1x1 canvas and draws no frame. */
    static BufferedImage paintNow(RemotePanel panel) {
        BufferedImage canvas = new BufferedImage(Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            panel.paint(g);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** What the snapshot timer plus a repaint do: apply on the EDT, then paint what was applied. */
    static void applyAndPaint(RemotePanel panel, Snapshot s) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.apply(s);
            paintNow(panel);
        });
    }
}
