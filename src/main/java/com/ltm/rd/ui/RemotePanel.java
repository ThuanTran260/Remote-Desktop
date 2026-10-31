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
 * whose frame is on screen, so input is allowed only for a frame the user has actually seen.
 *
 * <p>The gate is opened by {@link #paintComponent}, not by {@link #apply}: applying a snapshot that
 * allows control only records a pending gate, and it opens once that exact frame image has been drawn.
 * Closing is immediate. Within one (generation, epoch) an open gate stays open when a fresher frame
 * is applied, so input does not flicker off on every frame.
 */
public final class RemotePanel extends JPanel {
    static final Color BACKGROUND = new Color(0x202020);

    /** The (generation, epoch) of the painted frame; sendInput must present exactly these. */
    public record InputGate(long generation, long inputEpoch) {}

    /** A gate that is waiting for {@code image} to be drawn before it may open. */
    private record PendingGate(long generation, long inputEpoch, BufferedImage image) {}

    // EDT only
    private BufferedImage frame;
    private int remoteW;
    private int remoteH;
    private long generation = Long.MIN_VALUE;
    private long epoch = Long.MIN_VALUE;
    private InputGate gate;
    private PendingGate pending;

    public RemotePanel() {
        super(null);
        setOpaque(true);
        setBackground(BACKGROUND);
    }

    /**
     * Applies a snapshot; one older than the applied one (smaller generation, or the same generation
     * and a smaller epoch) is ignored. Generation 0 means "no session" and always applies, so a closed
     * session clears the frame and the gate.
     *
     * <p>Anything that closes the gate (control disabled, no frame, no session, or a newer generation
     * or epoch) takes effect at once. A snapshot that allows control only records a pending gate,
     * which {@link #paintComponent} opens after drawing that frame; the exception is a snapshot with
     * the (generation, epoch) of an already open gate, which leaves the gate open.
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
        if (noSession || !s.controlEnabled() || frame == null) {
            gate = null;
            pending = null;
        } else if (gate == null || gate.generation() != generation || gate.inputEpoch() != epoch) {
            gate = null; // a new (generation, epoch) must be seen again before input is allowed
            pending = new PendingGate(generation, epoch, frame);
        } else {
            pending = null; // already open for this (generation, epoch): a fresher frame keeps it open
        }
        repaint();
    }

    /** Present once the frame of an applied snapshot that allows control has been painted. EDT only. */
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

    /**
     * Called right after {@code drawn} was drawn. The pending gate opens only for that exact image and
     * only while its (generation, epoch) is still the panel's current one, so painting an older frame
     * never opens a newer pending gate.
     */
    private void openPendingGate(BufferedImage drawn) {
        PendingGate p = pending;
        if (p != null && p.image() == drawn && p.generation() == generation && p.inputEpoch() == epoch) {
            gate = new InputGate(p.generation(), p.inputEpoch());
            pending = null;
        }
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
            openPendingGate(image);
        } finally {
            g2.dispose();
        }
    }
}
