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

/** Viewer form: connection attempts, input errors and the active control window. */
final class ClientFrame extends JFrame {
    private final Controller controller;
    private final JTextField host = new JTextField(16);
    private final JTextField code = new JTextField(8);
    private final JButton connect = new JButton("Kết nối");
    private final JButton cancel = new JButton("Hủy");
    private final JButton stop = new JButton("Ngắt");
    private final JLabel status = new JLabel(" ");
    private ControlFrame control;
    private Snapshot lastApplied;
    private boolean disposed;
    private boolean closing;
    private final ClosingWatch closingWatch = new ClosingWatch(System::nanoTime);
    private final SnapshotFeed feed;
    private ClientFormState.PendingText pendingText;

    ClientFrame(Controller controller, String presetHost, Runnable onExit) {
        super("Viewer — Máy điều khiển");
        this.controller = controller;
        if (presetHost != null) host.setText(presetHost); // pre-fill only: never connects by itself

        JPanel content = new JPanel(new GridLayout(0, 1, 4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        content.add(new JLabel("IP máy Host:"));
        content.add(host);
        content.add(new JLabel("Mã kết nối (6 chữ số):"));
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

        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener() {
            private void changed() {
                pendingText = null;
                if (lastApplied != null) status.setText(ClientFormState.of(lastApplied, false).statusText());
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        host.getDocument().addDocumentListener(edited);
        code.getDocument().addDocumentListener(edited);

        feed = new SnapshotFeed(controller, this::apply);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { // closing is the same as Stop
                if (closing) return;
                closing = true;
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
        pendingText = ClientFormState.pendingFor(outcome, lastApplied == null ? 0 : lastApplied.generation());
        if (outcome == ConnectOutcome.STARTED) code.setText("");
        if (pendingText != null) status.setText(pendingText.text());
    }

    /** EDT: shows the newest snapshot. */
    private void apply(Snapshot s) {
        ClientFormState st = ClientFormState.of(s, closingWatch.warning(s));
        host.setEnabled(st.fieldsEnabled());
        code.setEnabled(st.fieldsEnabled());
        connect.setEnabled(st.connectEnabled());
        cancel.setVisible(st.cancelVisible());
        stop.setVisible(st.disconnectVisible());
        lastApplied = s;
        if (pendingText != null) pendingText = pendingText.retainFor(s);
        status.setText(pendingText == null ? st.statusText() : pendingText.text());
        if (st.controlWindowVisible()) {
            if (control == null) {
                control = new ControlFrame(controller, feed::latest);
                control.setVisible(true);
            }
            control.apply(s);
        } else closeControl();
    }

    private void closeControl() {
        if (control != null) {
            control.dispose();
            control = null;
        }
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        feed.close();
        closeControl();
        super.dispose();
    }
}
