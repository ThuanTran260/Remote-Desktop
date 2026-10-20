package com.ltm.rd.ui;

import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Window of the controlled machine. Pressing Connect is the user's consent to share the screen;
 * while ACTIVE the always-on-top {@link SharingBar} shows who controls it and a Stop button. The
 * host given on the command line only pre-fills the field.
 */
final class ClientFrame extends JFrame {
    private final Controller controller;
    private final JTextField host = new JTextField(16);
    private final JTextField code = new JTextField(8);
    private final JButton connect = new JButton("Kết nối");
    private final JButton cancel = new JButton("Hủy");
    private final JButton stop = new JButton("Dừng");
    private final JLabel status = new JLabel(" ");
    private final SharingBar sharingBar;
    private final ClosingWatch closingWatch = new ClosingWatch(System::nanoTime);
    private final SnapshotFeed feed;
    private String pendingText; // why connect() did not start, until the next real state

    ClientFrame(Controller controller, String presetHost, Runnable onExit) {
        super("Máy được điều khiển (Client)");
        this.controller = controller;
        sharingBar = new SharingBar(controller);
        if (presetHost != null) host.setText(presetHost); // pre-fill only: never connects by itself

        JPanel content = new JPanel(new GridLayout(0, 1, 4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        content.add(new JLabel("IP máy điều khiển:"));
        content.add(host);
        content.add(new JLabel("Mã (6 chữ số):"));
        content.add(code);
        JPanel buttons = new JPanel(new GridLayout(1, 0, 8, 0));
        buttons.add(connect);
        buttons.add(cancel);
        buttons.add(stop);
        content.add(buttons);
        content.add(status);
        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        connect.addActionListener(e -> connectNow());
        code.addActionListener(e -> {
            if (connect.isEnabled()) connectNow();
        });
        cancel.addActionListener(e -> controller.cancel());
        stop.addActionListener(e -> controller.disconnect());

        feed = new SnapshotFeed(controller, this::apply);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { // closing is the same as Stop
                controller.disconnect();
                feed.close();
                sharingBar.dispose();
                dispose();
                onExit.run();
            }
        });
        pack();
        setLocationRelativeTo(null);
        feed.start();
    }

    private void connectNow() {
        ConnectOutcome outcome = controller.connect(host.getText(), code.getText());
        pendingText = outcome == ConnectOutcome.STARTED ? null : ClientFormState.textFor(outcome);
        if (pendingText != null) status.setText(pendingText);
    }

    /** EDT: shows the newest snapshot. */
    private void apply(Snapshot s) {
        ClientFormState st = ClientFormState.of(s, closingWatch.warning(s));
        host.setEnabled(st.fieldsEnabled());
        code.setEnabled(st.fieldsEnabled());
        connect.setEnabled(st.connectEnabled());
        cancel.setVisible(st.cancelVisible());
        stop.setVisible(st.disconnectVisible());
        if (s.phase() == UiPhase.IDLE && s.message().isEmpty() && pendingText != null) {
            status.setText(pendingText);
        } else {
            pendingText = null;
            status.setText(st.statusText());
        }
        if (st.sharingBarVisible()) {
            sharingBar.showFor(st.statusText());
        } else {
            sharingBar.hideBar();
        }
    }
}
