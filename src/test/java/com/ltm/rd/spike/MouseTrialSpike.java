package com.ltm.rd.spike;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.client.RobotAdapterFactory;
import com.ltm.rd.ui.AppConfig;
import com.ltm.rd.ui.AppRuntime;
import com.ltm.rd.ui.ConnectOutcome;
import com.ltm.rd.ui.InputCapture;
import com.ltm.rd.ui.RemotePanel;
import com.ltm.rd.ui.Snapshot;
import com.ltm.rd.ui.SnapshotApplier;
import com.ltm.rd.ui.SnapshotPump;
import com.ltm.rd.ui.SnapshotTimer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Manual trial window for the mouse (Task 3.4) and the keyboard (Task 3.5) on two real
 * Windows machines. Not run by {@code mvn verify}: the class name matches no Surefire pattern.
 * It drives the production runtime, capture and panel classes with the real
 * {@link RobotAdapterFactory}; only the window around them is temporary.
 *
 * <pre>
 * ./mvnw.cmd -q test-compile
 * java -cp "target/classes;target/test-classes" com.ltm.rd.spike.MouseTrialSpike server
 * java -cp "target/classes;target/test-classes" com.ltm.rd.spike.MouseTrialSpike client SERVER_IP CODE
 * java -cp "target/classes;target/test-classes" com.ltm.rd.spike.MouseTrialSpike loopback
 * </pre>
 *
 * <ul>
 *   <li>{@code server}: the controlling machine; shows the control window with the code and the
 *       remote screen.</li>
 *   <li>{@code client SERVER_IP CODE}: the controlled machine; shares its screen through Robot.</li>
 *   <li>{@code loopback}: both roles in one JVM, for a first look without a second machine.</li>
 * </ul>
 *
 * Closing a window always runs the full shutdown, which releases every key and button the
 * controlled side still holds. Results go to
 * {@code docs/superpowers/reviews/2026-10-20-wave3-mouse-trial-results.md} by hand.
 */
public final class MouseTrialSpike {
    private static final int POLL_MS = 50;

    private MouseTrialSpike() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("MouseTrialSpike needs a display.");
            System.exit(2);
        }
        String mode = args.length > 0 ? args[0] : "loopback";
        switch (mode) {
            case "server" -> {
                AppRuntime server = startServer();
                SwingUtilities.invokeLater(() -> openControlWindow(server, null));
            }
            case "client" -> {
                if (args.length < 3) {
                    System.err.println("usage: client SERVER_IP CODE");
                    System.exit(2);
                }
                AppRuntime client = startClient(args[1], args[2]);
                SwingUtilities.invokeLater(() -> openClientWindow(client));
            }
            case "loopback" -> {
                AppRuntime server = startServer();
                Snapshot listening = server.snapshot();
                AppRuntime client = startClient("127.0.0.1", listening.code(), listening.cmdPort(),
                        listening.imgPort());
                SwingUtilities.invokeLater(() -> openControlWindow(server, client));
            }
            default -> {
                System.err.println("unknown mode, use server, client or loopback");
                System.exit(2);
            }
        }
    }

    private static AppRuntime startServer() throws Exception {
        AppRuntime server = AppRuntime.forServer(AppConfig.from(LaunchOptions.parse(new String[] {"--server"})));
        server.startServer().get(10, TimeUnit.SECONDS);
        return server;
    }

    private static AppRuntime startClient(String host, String code) throws Exception {
        AppConfig cfg = AppConfig.from(LaunchOptions.parse(new String[] {"--client"}));
        return startClient(host, code, cfg);
    }

    private static AppRuntime startClient(String host, String code, int cmdPort, int imgPort) throws Exception {
        AppConfig base = AppConfig.from(LaunchOptions.parse(new String[] {"--client"}));
        return startClient(host, code, new AppConfig(base.bindAddress(), cmdPort, imgPort, host, base.timeouts(),
                base.nanoClock(), base.shutdownBudget()));
    }

    private static AppRuntime startClient(String host, String code, AppConfig cfg) throws Exception {
        AppRuntime client = AppRuntime.forClient(cfg, new RobotAdapterFactory());
        ConnectOutcome outcome = client.connect(host, code);
        if (outcome != ConnectOutcome.STARTED) {
            System.err.println("connect refused: " + outcome);
        }
        return client;
    }

    /** The control window: remote screen, status line, and the capture wired to the panel. */
    private static void openControlWindow(AppRuntime server, AppRuntime loopbackClient) {
        RemotePanel panel = new RemotePanel();
        panel.setPreferredSize(new Dimension(960, 540));
        panel.setFocusable(true);
        InputCapture capture = new InputCapture(server, panel, panel::hasFocus, panel::requestFocusInWindow);
        panel.addMouseListener(capture);
        panel.addMouseMotionListener(capture);
        panel.addMouseWheelListener(capture);
        panel.addKeyListener(capture); // Task 3.5: key codes only, Tab included
        panel.addFocusListener(capture);

        JLabel status = new JLabel(" ");
        JButton disconnect = new JButton("Disconnect");
        JFrame frame = new JFrame("Mouse trial - control window");
        frame.setLayout(new BorderLayout());
        frame.add(status, BorderLayout.NORTH);
        frame.add(panel, BorderLayout.CENTER);
        frame.add(disconnect, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.pack();

        SnapshotPump pump = new SnapshotPump(server, Duration.ofMillis(POLL_MS));
        SnapshotTimer timer = new SnapshotTimer(new SnapshotApplier(pump, s -> {
            panel.apply(s);
            capture.onApplied(s.generation(), s.inputEpoch());
            status.setText(s.phase() + (s.code() == null ? "" : "  code " + s.code()) + "  " + s.message());
        }), POLL_MS);
        timer.start();

        Runnable exit = () -> {
            capture.disconnecting(); // asks the controlled side to release everything before the sockets close
            timer.stop();
            pump.close();
            frame.dispose();
            shutdownAndExit(server, loopbackClient);
        };
        disconnect.addActionListener(e -> {
            capture.disconnecting();
            server.disconnect();
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit.run();
            }

            @Override
            public void windowDeactivated(WindowEvent e) { // Alt+Tab away while holding a key
                capture.windowDeactivated();
            }
        });
        frame.setVisible(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownQuietly(server, loopbackClient)));
    }

    /** The controlled side: a status line and a way to disconnect. */
    private static void openClientWindow(AppRuntime client) {
        JLabel status = new JLabel(" ");
        JButton disconnect = new JButton("Disconnect");
        JFrame frame = new JFrame("Mouse trial - controlled machine");
        frame.setLayout(new BorderLayout());
        frame.add(status, BorderLayout.CENTER);
        frame.add(disconnect, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(420, 120);

        SnapshotPump pump = new SnapshotPump(client, Duration.ofMillis(POLL_MS));
        SnapshotTimer timer = new SnapshotTimer(new SnapshotApplier(pump,
                s -> status.setText(s.phase() + "  " + s.message())), POLL_MS);
        timer.start();
        disconnect.addActionListener(e -> client.disconnect());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                timer.stop();
                pump.close();
                frame.dispose();
                shutdownAndExit(client, null);
            }
        });
        frame.setVisible(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownQuietly(client, null)));
    }

    /** Off the EDT: the shutdown waits for the controlled side to confirm that input was released. */
    private static void shutdownAndExit(AppRuntime first, AppRuntime second) {
        Thread t = new Thread(() -> {
            shutdownQuietly(first, second);
            System.exit(0);
        }, "rd-trial-exit");
        t.setDaemon(true);
        t.start();
    }

    private static void shutdownQuietly(AppRuntime first, AppRuntime second) {
        for (AppRuntime rt : new AppRuntime[] {second, first}) { // the controlled side first: it releases input
            if (rt == null) continue;
            try {
                rt.shutdownAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("shutdown incomplete: " + e.getClass().getSimpleName());
            }
        }
    }
}
