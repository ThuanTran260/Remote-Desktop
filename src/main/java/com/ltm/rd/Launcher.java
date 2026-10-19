package com.ltm.rd;

/** Entry point; all parsing logic lives in {@link LaunchOptions}. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.print(LaunchOptions.USAGE);
            System.exit(0);
            return;
        }
        System.err.println(options.role().name().toLowerCase() + ": not implemented yet");
        System.exit(3);
    }
}
