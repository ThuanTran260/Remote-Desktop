package com.ltm.rd.ui;

import java.awt.Rectangle;
import java.util.Optional;

/**
 * Maps panel pixels to remote screen pixels. {@link #imageRect} is the one rounded rectangle that
 * both the renderer draws into and the mapper converts against, so what the user sees under the
 * pointer is exactly what the remote receives. Pure functions, no Swing state.
 */
public final class CoordinateMapper {
    private CoordinateMapper() {}

    public record RemotePoint(int x, int y) {}

    /**
     * The letterboxed, aspect-preserving rectangle of the remote screen inside the panel, rounded
     * half up. Empty if the panel or the remote has a side {@code <= 0} (or the result has no area).
     */
    public static Optional<Rectangle> imageRect(int panelW, int panelH, int remoteW, int remoteH) {
        if (panelW <= 0 || panelH <= 0 || remoteW <= 0 || remoteH <= 0) return Optional.empty();
        // scale = min(pw / rw, ph / rh), decided with exact products so a .5 tie is never lost to rounding
        double dw;
        double dh;
        if ((long) panelW * remoteH <= (long) panelH * remoteW) { // width-limited
            dw = panelW;
            dh = (double) remoteH * panelW / remoteW;
        } else {
            dh = panelH;
            dw = (double) remoteW * panelH / remoteH;
        }
        double ox = (panelW - dw) / 2;
        double oy = (panelH - dh) / 2;
        Rectangle rect = new Rectangle((int) Math.round(ox), (int) Math.round(oy), (int) Math.round(dw),
                (int) Math.round(dh));
        return rect.width <= 0 || rect.height <= 0 ? Optional.empty() : Optional.of(rect);
    }

    /** Only a point inside {@link #imageRect} maps; anything else is empty. */
    public static Optional<RemotePoint> map(int mx, int my, int panelW, int panelH, int remoteW, int remoteH) {
        Optional<Rectangle> rect = imageRect(panelW, panelH, remoteW, remoteH);
        if (rect.isEmpty() || !rect.get().contains(mx, my)) return Optional.empty();
        return Optional.of(convert(mx, my, rect.get(), remoteW, remoteH));
    }

    /** Like {@link #map} but a point outside is clamped to {@code [0, remote-1]}; for drags and releases. */
    public static Optional<RemotePoint> mapClamped(int mx, int my, int panelW, int panelH, int remoteW,
            int remoteH) {
        Optional<Rectangle> rect = imageRect(panelW, panelH, remoteW, remoteH);
        if (rect.isEmpty()) return Optional.empty();
        RemotePoint p = convert(mx, my, rect.get(), remoteW, remoteH);
        return Optional.of(new RemotePoint(clamp(p.x(), remoteW), clamp(p.y(), remoteH)));
    }

    private static RemotePoint convert(int mx, int my, Rectangle rect, int remoteW, int remoteH) {
        int x = (int) Math.floor((mx - rect.x) * (double) remoteW / rect.width);
        int y = (int) Math.floor((my - rect.y) * (double) remoteH / rect.height);
        return new RemotePoint(x, y);
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }
}
