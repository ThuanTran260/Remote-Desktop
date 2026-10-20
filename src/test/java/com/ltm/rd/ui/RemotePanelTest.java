package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Headless: a plain JPanel subclass painted into a BufferedImage, applied through the EDT. */
@Timeout(60)
class RemotePanelTest {
    private static final int BACKGROUND = 0x202020;
    private static final int RED = 0xFF0000;

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static Snapshot snapshot(long generation, long epoch, boolean enabled, int w, int h, BufferedImage frame) {
        return new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, generation, epoch, "", "10.0.0.2", 5001, 5000,
                "123456", w, h, frame, 1, enabled, TrafficStats.ZERO);
    }

    private static void onEdt(Runnable r) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T onEdt(java.util.function.Supplier<T> s) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(s.get()));
        return out.get();
    }

    private static BufferedImage paint(RemotePanel panel) throws Exception {
        BufferedImage canvas = new BufferedImage(Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        onEdt(() -> {
            Graphics2D g = canvas.createGraphics();
            panel.paint(g);
            g.dispose();
        });
        return canvas;
    }

    private static RemotePanel panel(int w, int h) {
        RemotePanel p = new RemotePanel();
        p.setSize(w, h);
        return p;
    }

    @Test
    void paintsLetterboxWithBackgroundAndImageInside() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(1, 0, true, 1920, 1080, solid(1920, 1080, RED))));
        BufferedImage out = paint(panel);
        assertEquals(BACKGROUND, out.getRGB(10, 10) & 0xFFFFFF, "left bar");
        assertEquals(BACKGROUND, out.getRGB(990, 390) & 0xFFFFFF, "right bar");
        assertEquals(RED, out.getRGB(500, 200) & 0xFFFFFF, "image");
    }

    @Test
    void paintAndMapperShareTheSameRect() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(1, 0, true, 1600, 900, solid(1600, 900, RED))));
        BufferedImage out = paint(panel);
        Rectangle rect = CoordinateMapper.imageRect(1000, 400, 1600, 900).orElseThrow(); // (144, 0, 711, 400)
        // inside the border pixels the panel painted the image, one pixel outside it the background
        assertEquals(RED, out.getRGB(rect.x + 2, 200) & 0xFFFFFF);
        assertEquals(RED, out.getRGB(rect.x + rect.width - 3, 200) & 0xFFFFFF);
        assertEquals(BACKGROUND, out.getRGB(rect.x - 2, 200) & 0xFFFFFF);
        assertEquals(BACKGROUND, out.getRGB(rect.x + rect.width + 1, 200) & 0xFFFFFF);
        // and the mapper agrees about which side of the edge a pointer is on
        assertTrue(panel.toRemote(rect.x, 200).isPresent());
        assertTrue(panel.toRemote(rect.x - 1, 200).isEmpty());
        assertTrue(panel.toRemote(rect.x + rect.width - 1, 200).isPresent());
        assertTrue(panel.toRemote(rect.x + rect.width, 200).isEmpty());
    }

    @Test
    void noScreenInfoPaintsNothingAndMapsEmpty() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(1, 0, true, 0, 0, solid(10, 10, RED))));
        BufferedImage out = paint(panel);
        assertEquals(BACKGROUND, out.getRGB(500, 200) & 0xFFFFFF);
        assertTrue(panel.toRemote(500, 200).isEmpty());
        assertTrue(panel.toRemoteClamped(500, 200).isEmpty());
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty(), "no screen: no input");
    }

    @Test
    void panelZeroSizeMapsEmpty() throws Exception {
        RemotePanel panel = panel(0, 0);
        onEdt(() -> panel.apply(snapshot(1, 0, true, 1920, 1080, solid(8, 8, RED))));
        assertTrue(panel.toRemote(0, 0).isEmpty());
        assertTrue(panel.toRemoteClamped(0, 0).isEmpty());
        assertEquals(1920, panel.remoteWidth());
        assertEquals(1080, panel.remoteHeight());
    }

    @Test
    void inputGateEmptyBeforeFirstApply() throws Exception {
        RemotePanel panel = panel(100, 100);
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty());
    }

    @Test
    void inputGateEmptyWhenControlDisabled() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(1, 0, false, 1920, 1080, solid(1920, 1080, RED))));
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty());
        assertEquals(RED, paint(panel).getRGB(500, 200) & 0xFFFFFF, "the frame is still drawn");
    }

    @Test
    void inputGateEmptyWithoutFrame() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(1, 0, true, 1920, 1080, null)));
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty());
    }

    @Test
    void inputGateCarriesGenerationAndEpochOfAppliedSnapshot() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(7, 3, true, 1920, 1080, solid(8, 8, RED))));
        assertEquals(new RemotePanel.InputGate(7, 3), onEdt(() -> panel.inputGate()).orElseThrow());
    }

    @Test
    void olderSnapshotIsIgnored() throws Exception {
        RemotePanel panel = panel(1000, 400);
        BufferedImage newest = solid(8, 8, RED);
        onEdt(() -> panel.apply(snapshot(7, 3, true, 1920, 1080, newest)));
        onEdt(() -> panel.apply(snapshot(6, 9, true, 1280, 720, solid(8, 8, 0x00FF00))));
        onEdt(() -> panel.apply(snapshot(7, 2, true, 1280, 720, solid(8, 8, 0x00FF00))));
        assertEquals(new RemotePanel.InputGate(7, 3), onEdt(() -> panel.inputGate()).orElseThrow());
        assertEquals(1920, panel.remoteWidth());
        // a newer epoch of the same generation does apply, and a stall closes the gate
        onEdt(() -> panel.apply(snapshot(7, 4, false, 1920, 1080, newest)));
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty());
    }

    @Test
    void noSessionSnapshotClearsFrameAndGate() throws Exception {
        RemotePanel panel = panel(1000, 400);
        onEdt(() -> panel.apply(snapshot(7, 3, true, 1920, 1080, solid(8, 8, RED))));
        onEdt(() -> panel.apply(snapshot(0, 0, false, 0, 0, null))); // the session ended
        assertTrue(onEdt(() -> panel.inputGate()).isEmpty());
        assertTrue(panel.toRemote(500, 200).isEmpty());
        assertEquals(BACKGROUND, paint(panel).getRGB(500, 200) & 0xFFFFFF);
        // a later session, even with a smaller generation number than before, applies again
        onEdt(() -> panel.apply(snapshot(2, 0, true, 1920, 1080, solid(8, 8, RED))));
        assertEquals(new RemotePanel.InputGate(2, 0), onEdt(() -> panel.inputGate()).orElseThrow());
    }

    @Test
    void applyOffEdtThrows() throws Exception {
        RemotePanel panel = panel(100, 100);
        Snapshot s = snapshot(1, 0, true, 10, 10, solid(2, 2, RED));
        assertThrows(IllegalStateException.class, () -> panel.apply(s));
        assertThrows(IllegalStateException.class, panel::inputGate);
        onEdt(() -> panel.apply(s)); // runs on the EDT
        assertTrue(onEdt(() -> panel.inputGate()).isPresent());
    }
}
