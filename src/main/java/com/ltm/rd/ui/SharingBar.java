package com.ltm.rd.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;

/**
 * The always-on-top bar of the controlled machine: who controls it, and a prominent Stop button.
 * Shown for as long as the session is ACTIVE so the user can always see and end the sharing.
 */
final class SharingBar extends JFrame {
    private final JLabel label = new JLabel(" ");
    private final BarLayoutGate gate = new BarLayoutGate();

    SharingBar(Controller controller) {
        super("Đang chia sẻ màn hình");
        // never take keyboard focus: injected keys must not reach this bar; the mouse still works
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new FlowLayout(FlowLayout.CENTER, 12, 6));
        getRootPane().setBorder(BorderFactory.createLineBorder(new Color(0xB71C1C), 2));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        JButton stop = new JButton("Dừng");
        stop.setFont(stop.getFont().deriveFont(Font.BOLD, 14f));
        stop.setBackground(new Color(0xC62828));
        stop.setForeground(Color.WHITE);
        stop.setOpaque(true);
        stop.setPreferredSize(new Dimension(90, 30));
        stop.addActionListener(e -> controller.disconnect());
        add(label);
        add(stop);
    }

    /** EDT. */
    void showFor(String text) {
        boolean relayout = gate.needsLayout(text, isVisible());
        if (!relayout) return;
        label.setText(text);
        pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setLocation(screen.x + (screen.width - getWidth()) / 2, screen.y);
        if (!isVisible()) setVisible(true);
    }

    /** EDT. */
    void hideBar() {
        if (isVisible()) setVisible(false);
    }
}
