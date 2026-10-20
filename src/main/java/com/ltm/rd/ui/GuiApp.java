package com.ltm.rd.ui;

import com.ltm.rd.Launcher;
import com.ltm.rd.LaunchOptions;
import com.ltm.rd.client.RobotAdapterFactory;
import java.awt.EventQueue;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

/**
 * Starts the interface on the EDT. The runtime, the adapters and the address lookup are created off
 * the EDT on a short-lived {@code rd-gui-init} thread; closing a main window runs the runtime's
 * shutdown (which releases any held input) in the background and then ends the process.
 */
public final class GuiApp {
    private GuiApp() {}

    /** Schedules the start and returns at once; the process stays alive through the open windows. */
    public static int launch(LaunchOptions options) {
        EventQueue.invokeLater(() -> start(options));
        return Launcher.STAY_RUNNING;
    }

    private static void start(LaunchOptions options) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // the default look and feel is fine
        }
        if (options.role() == LaunchOptions.Role.CHOOSER) {
            RoleChooserFrame[] chooser = new RoleChooserFrame[1];
            chooser[0] = new RoleChooserFrame(role -> {
                chooser[0].dispose();
                openRole(new LaunchOptions(role, false, options.cmdPort(), options.imgPort(), options.host()));
            }, () -> System.exit(0));
            chooser[0].setVisible(true);
        } else {
            openRole(options);
        }
    }

    private static void openRole(LaunchOptions options) {
        Thread init = new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.from(options);
                if (options.role() == LaunchOptions.Role.SERVER) {
                    AppRuntime runtime = AppRuntime.forServer(cfg);
                    List<String> addresses = LocalAddresses.ipv4();
                    EventQueue.invokeLater(() -> showServer(runtime, addresses));
                } else {
                    AppRuntime runtime = AppRuntime.forClient(cfg, new RobotAdapterFactory());
                    EventQueue.invokeLater(() -> showClient(runtime, options.host()));
                }
            } catch (Exception e) {
                String text = Messages.forThrowable(e);
                EventQueue.invokeLater(() -> fail(text));
            }
        }, "rd-gui-init");
        init.setDaemon(true);
        init.start();
    }

    private static void showServer(AppRuntime runtime, List<String> addresses) {
        ServerFrame frame = new ServerFrame(runtime, addresses, () -> exitAfterShutdown(runtime));
        frame.setVisible(true);
        runtime.startServer(); // a failure shows up as FAILED in the next snapshot
    }

    private static void showClient(AppRuntime runtime, String presetHost) {
        ClientFrame frame = new ClientFrame(runtime, presetHost, () -> exitAfterShutdown(runtime));
        frame.setVisible(true);
    }

    private static void fail(String text) {
        JOptionPane.showMessageDialog(null, text, "Không khởi động được", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    /** The shutdown runs on {@code rd-shutdown}; the EDT only starts it. */
    private static void exitAfterShutdown(AppRuntime runtime) {
        runtime.shutdownAsync().whenComplete((report, error) -> System.exit(0));
    }
}
