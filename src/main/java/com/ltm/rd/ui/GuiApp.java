package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.Launcher;
import com.ltm.rd.host.RobotAdapterFactory;
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
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // the default look and feel is fine
            }
            if (options.role() == LaunchOptions.Role.CHOOSER) {
                RoleChooserFrame[] chooser = new RoleChooserFrame[1];
                chooser[0] = new RoleChooserFrame(role -> {
                    // start the non-daemon init thread before the chooser goes away, so no gap exists
                    openRole(new LaunchOptions(role, false, options.cmdPort(), options.imgPort(), options.address()));
                    chooser[0].dispose();
                }, () -> System.exit(0));
                chooser[0].setVisible(true);
            } else {
                openRole(options);
            }
        } catch (Throwable t) {
            fail(Messages.forThrowable(t));
        }
    }

    private static void openRole(LaunchOptions options) {
        Thread init = new Thread(() -> {
            AppRuntime created = null;
            try {
                AppConfig cfg = AppConfig.from(options);
                if (options.role() == LaunchOptions.Role.HOST) {
                    created = AppRuntime.forHost(cfg, new RobotAdapterFactory());
                    AppRuntime runtime = created;
                    List<String> addresses = LocalAddresses.ipv4();
                    EventQueue.invokeLater(() -> showServer(runtime, addresses));
                } else {
                    created = AppRuntime.forViewer(cfg);
                    AppRuntime runtime = created;
                    EventQueue.invokeLater(() -> showClient(runtime, options.address()));
                }
            } catch (Throwable t) {
                String text = Messages.forThrowable(t);
                if (created == null) EventQueue.invokeLater(() -> fail(text));
                else rollback(created, text);
            }
        }, "rd-gui-init");
        // non-daemon: keeps the JVM alive until the role window is shown (AWT auto-shutdown otherwise)
        init.setDaemon(false);
        init.start();
    }

    private static void showServer(AppRuntime runtime, List<String> addresses) {
        try {
            HostFrame frame = new HostFrame(runtime, addresses, () -> exitAfterShutdown(runtime));
            frame.setVisible(true);
            runtime.startHost(); // a failure shows up as FAILED in the next snapshot
        } catch (Throwable t) {
            rollback(runtime, Messages.forThrowable(t));
        }
    }

    private static void showClient(AppRuntime runtime, String presetHost) {
        try {
            ViewerFrame frame = new ViewerFrame(runtime, presetHost, () -> exitAfterShutdown(runtime));
            frame.setVisible(true);
        } catch (Throwable t) {
            rollback(runtime, Messages.forThrowable(t));
        }
    }

    private static void fail(String text) {
        JOptionPane.showMessageDialog(null, text, "Không khởi động được", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    /** The shutdown runs on {@code rd-shutdown}; the EDT only starts it. */
    private static void exitAfterShutdown(AppRuntime runtime) {
        exitAfterShutdown(runtime, GuiApp::warnShutdown, () -> System.exit(0));
    }

    static void exitAfterShutdown(Controller controller, java.util.function.Consumer<String> warning, Runnable exit) {
        try {
            controller.shutdownAsync().whenComplete((report, error) -> EventQueue.invokeLater(() -> {
                String text = Messages.forShutdown(error == null ? report : null);
                try { if (!text.isEmpty()) warning.accept(text); }
                finally { exit.run(); }
            }));
        } catch (Throwable failure) {
            EventQueue.invokeLater(() -> {
                try { warning.accept(Messages.forShutdown(null)); }
                finally { exit.run(); }
            });
        }
    }

    private static void warnShutdown(String text) {
        JOptionPane.showMessageDialog(null, text, "Chưa xác nhận dọn sạch", JOptionPane.WARNING_MESSAGE);
    }

    private static void rollback(AppRuntime runtime, String text) {
        exitAfterShutdown(runtime, GuiApp::warnShutdown, () -> fail(text));
    }
}
