package com.ltm.rd.ui;

import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Window of the controlling machine: its addresses, ports, the one-time code, the peer, and the
 * control window while a session is ACTIVE. It sees only the {@link Controller} and snapshots.
 */
final class ServerFrame extends JFrame {
    private final Controller controller;
    private final JLabel ports = new JLabel(" ");
    private final JLabel code = new JLabel("—");
    private final JLabel peer = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton regenerate = new JButton("Đổi mã");
    private final JButton disconnect = new JButton("Ngắt");
    private final JButton retry = new JButton("Thử lại");
    private final ClosingWatch closingWatch = new ClosingWatch(System::nanoTime);
    private final SnapshotFeed feed;
    private ControlFrame control; // EDT only

    ServerFrame(Controller controller, List<String> addresses, Runnable onExit) {
        super("Máy điều khiển (Server)");
        this.controller = controller;
        JPanel content = new JPanel(new GridLayout(0, 1, 4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        content.add(new JLabel("Địa chỉ IP: " + (addresses.isEmpty() ? "không tìm thấy" : String.join(", ", addresses))));
        content.add(ports);
        content.add(new JLabel("Mã kết nối:"));
        code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 44));
        content.add(code);
        content.add(peer);
        content.add(status);
        JPanel buttons = new JPanel(new GridLayout(1, 0, 8, 0));
        buttons.add(regenerate);
        buttons.add(disconnect);
        buttons.add(retry);
        content.add(buttons);
        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        regenerate.addActionListener(e -> controller.regenerateCode());
        disconnect.addActionListener(e -> controller.disconnect());
        retry.addActionListener(e -> controller.startServer());

        feed = new SnapshotFeed(controller, this::apply);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                feed.close();
                closeControl();
                dispose();
                onExit.run();
            }
        });
        pack();
        setLocationRelativeTo(null);
        feed.start();
    }

    /** EDT: shows the newest snapshot. */
    private void apply(Snapshot s) {
        ServerFormState st = ServerFormState.of(s, closingWatch.warning(s));
        regenerate.setEnabled(st.regenerateEnabled());
        disconnect.setEnabled(st.disconnectEnabled());
        retry.setVisible(s.phase() == UiPhase.FAILED || s.phase() == UiPhase.IDLE);
        ports.setText("Cổng lệnh: " + s.cmdPort() + "   Cổng ảnh: " + s.imgPort());
        code.setText(s.code() == null ? "—" : s.code());
        peer.setText(s.peer() == null ? " " : "Máy kết nối: " + s.peer());
        status.setText(st.statusText());
        if (st.controlWindowVisible()) {
            if (control == null) {
                control = new ControlFrame(controller, feed::latest);
                control.setVisible(true);
            }
            control.apply(s);
        } else {
            closeControl();
        }
    }

    private void closeControl() {
        if (control != null) {
            control.dispose();
            control = null;
        }
    }
}
