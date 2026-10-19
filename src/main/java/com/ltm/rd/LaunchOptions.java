package com.ltm.rd;

import java.util.HashSet;
import java.util.Set;

/** Immutable command-line options for the launcher. */
public record LaunchOptions(Role role, boolean help, int cmdPort, int imgPort, String host) {

    public enum Role { CHOOSER, SERVER, CLIENT }

    public static final int DEFAULT_CMD_PORT = 5001;
    public static final int DEFAULT_IMG_PORT = 5000;

    public static final String USAGE = """
            Usage: java -jar remote-desktop.jar [--server | --client] [options]

              --server          run on the controlling machine (listens, views and controls)
              --client          run on the controlled machine (shares its screen)
              (neither)         show the role chooser
              --cmd-port N      command port, 1..65535 (default 5001)
              --img-port N      image port, 1..65535 (default 5000)
              --host IP         server address (client only)
              --help            print this help and exit
            """;

    /**
     * Parses command-line arguments.
     *
     * @throws IllegalArgumentException with a human-readable message on any error
     */
    public static LaunchOptions parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }
        Set<String> seen = new HashSet<>();
        Role role = Role.CHOOSER;
        boolean help = false;
        int cmdPort = DEFAULT_CMD_PORT;
        int imgPort = DEFAULT_IMG_PORT;
        String host = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--server", "--client" -> {
                    if (seen.contains("--server") || seen.contains("--client")) {
                        if (seen.contains(flag)) {
                            throw new IllegalArgumentException("Duplicate option: " + flag);
                        }
                        throw new IllegalArgumentException("--server and --client are mutually exclusive");
                    }
                    seen.add(flag);
                    role = flag.equals("--server") ? Role.SERVER : Role.CLIENT;
                }
                case "--help" -> {
                    checkNew(seen, flag);
                    help = true;
                }
                case "--cmd-port" -> {
                    checkNew(seen, flag);
                    cmdPort = parsePort(flag, valueAt(args, ++i, flag));
                }
                case "--img-port" -> {
                    checkNew(seen, flag);
                    imgPort = parsePort(flag, valueAt(args, ++i, flag));
                }
                case "--host" -> {
                    checkNew(seen, flag);
                    host = valueAt(args, ++i, flag);
                    if (host.isBlank()) {
                        throw new IllegalArgumentException("Option --host requires a non-empty value");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }

        if (role == Role.SERVER && host != null) {
            throw new IllegalArgumentException("--host is only valid with --client");
        }
        if (cmdPort == imgPort) {
            throw new IllegalArgumentException(
                    "--cmd-port and --img-port must differ (both are " + cmdPort + ")");
        }
        return new LaunchOptions(role, help, cmdPort, imgPort, host);
    }

    private static void checkNew(Set<String> seen, String flag) {
        if (!seen.add(flag)) {
            throw new IllegalArgumentException("Duplicate option: " + flag);
        }
    }

    private static String valueAt(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Option " + flag + " requires a value");
        }
        return args[index];
    }

    private static int parsePort(String flag, String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Option " + flag + " expects an integer, got '" + value + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Option " + flag + " must be in 1..65535, got " + port);
        }
        return port;
    }
}
