package com.ltm.rd;

import com.ltm.rd.ui.GuiApp;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;

/**
 * Entry point. Parsing lives in {@link LaunchOptions}; {@link #run} decides the exit code without
 * calling {@code System.exit}, so every path is testable. Exit codes: 0 for {@code --help}, 2 for bad
 * arguments, 4 when a graphical environment is required but missing, 1 when the interface could not
 * be started; {@link #STAY_RUNNING} means the interface is open and the process must keep running.
 */
public final class Launcher {
    public static final int STAY_RUNNING = -1;

    /** Opens the interface for the parsed options and returns {@link #STAY_RUNNING} (or an exit code). */
    @FunctionalInterface
    interface GuiLauncher {
        int launch(LaunchOptions options) throws Exception;
    }

    private Launcher() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err, GraphicsEnvironment.isHeadless(), GuiApp::launch);
        if (code >= 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, boolean headless, GuiLauncher gui) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.print(LaunchOptions.USAGE);
            return 2;
        }
        if (options.help()) {
            out.print(LaunchOptions.USAGE);
            return 0;
        }
        if (headless) {
            err.println("graphical environment required (java.awt.headless=true)");
            return 4;
        }
        try {
            return gui.launch(options);
        } catch (Exception e) {
            // only the type: the message could carry an address or other details
            err.println("could not start the interface: " + e.getClass().getSimpleName());
            return 1;
        }
    }
}
