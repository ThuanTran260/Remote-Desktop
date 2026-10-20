package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** First window when no role was given on the command line. */
final class RoleChooserFrame extends JFrame {

    RoleChooserFrame(Consumer<LaunchOptions.Role> onChoose, Runnable onExit) {
        super("Điều khiển máy từ xa");
        JPanel content = new JPanel(new GridLayout(0, 1, 8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(new JLabel("Chọn vai trò của máy này:", SwingConstants.CENTER));
        JButton server = new JButton("Máy điều khiển (Server)");
        JButton client = new JButton("Máy được điều khiển (Client)");
        server.addActionListener(e -> onChoose.accept(LaunchOptions.Role.SERVER));
        client.addActionListener(e -> onChoose.accept(LaunchOptions.Role.CLIENT));
        content.add(server);
        content.add(client);
        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onExit.run();
            }
        });
        pack();
        setLocationRelativeTo(null);
    }
}
