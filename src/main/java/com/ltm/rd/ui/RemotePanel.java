package com.ltm.rd.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Optional;
import javax.swing.JPanel;

/**
 * Draws the remote screen letterboxed and converts pointer positions. It only ever sees a
 * {@link Snapshot}; the input gate it exposes carries the generation and epoch of the snapshot
 * whose frame is on screen, so input is allowed only for a frame the user can actually see.
 */
public final class RemotePanel extends JPanel {
    static final Color BACKGROUND = new Color(0x202020);

    /** The (generation, epoch) of the applied frame; sendInput must present exactly these. */
    public record InputGate(long generation, long inputEpoch) {}

    // EDT only
    private BufferedImage frame;
    private int remoteW;
    private int remoteH;
    private long generation = Long.MIN_VALUE;
    private long epoch = Long.MIN_VALUE;
    private InputGate gate;

    public RemotePanel() {
        super(null);
        setOpaque(true);
        setBackground(BACKGROUND);
    }

    /**
     * Applies a snapshot; one older than the applied one (smaller generation, or the same generation
     * and a smaller epoch) is ignored. Generation 0 means "no session" and always applies, so a closed
     * session clears the frame and the gate.
     */
    public void apply(Snapshot s) {
        Edt.requireEdt();
        boolean noSession = s.generation() == 0;
        if (!noSession && (s.generation() < generation || (s.generation() == generation && s.inputEpoch() < epoch))) {
            return;
        }
        generation = noSession ? Long.MIN_VALUE : s.generation();
        epoch = noSession ? Long.MIN_VALUE : s.inputEpoch();
        remoteW = Math.max(0, s.screenWidth());
        remoteH = Math.max(0, s.screenHeight());
        frame = remoteW > 0 && remoteH > 0 ? s.frame() : null;
        gate = s.controlEnabled() && frame != null ? new InputGate(s.generation(), s.inputEpoch()) : null;
        repaint();
    }

    /** Present when the applied snapshot allows control and its frame is shown. EDT only. */
    public Optional<InputGate> inputGate() {
        Edt.requireEdt();
        return Optional.ofNullable(gate);
    }

    public Optional<CoordinateMapper.RemotePoint> toRemote(int mx, int my) {
        return CoordinateMapper.map(mx, my, getWidth(), getHeight(), remoteW, remoteH);
    }

    public Optional<CoordinateMapper.RemotePoint> toRemoteClamped(int mx, int my) {
        return CoordinateMapper.mapClamped(mx, my, getWidth(), getHeight(), remoteW, remoteH);
    }

    public int remoteWidth() {
        return remoteW;
    }

    public int remoteHeight() {
        return remoteH;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage image = frame;
            Optional<Rectangle> rect = CoordinateMapper.imageRect(getWidth(), getHeight(), remoteW, remoteH);
            if (image == null || rect.isEmpty()) return;
            Rectangle r = rect.get(); // the same rectangle the mapper converts against
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, r.x, r.y, r.width, r.height, null);
        } finally {
            g2.dispose();
        }
    }
}
