package com.ltm.rd;

import java.util.HashSet;
import java.util.Set;

/** Immutable command-line options for the launcher. */
public record LaunchOptions(Role role, boolean help, int cmdPort, int imgPort, String address) {

    public enum Role { CHOOSER, HOST, VIEWER }

    public static final int DEFAULT_CMD_PORT = 5001;
    public static final int DEFAULT_IMG_PORT = 5000;

    public static final String USAGE = """
            Usage: java -jar remote-desktop.jar [--role host|viewer] [options]
              --role host       controlled machine; listens and displays its connection code
              --role viewer     controlling machine; enters Host address and code
              (no --role)       show the role chooser
              --address ADDRESS pre-fill Host address; requires --role viewer; never auto-connects
              --cmd-port N      command port, 1..65535 (default 5001)
              --img-port N      image port, 1..65535 (default 5000; must differ from command port)
              --help            validate arguments, print help and exit
            Legacy --server, --client and --host IP are rejected; use the v2 role on the correct machine.
            Enter the six-digit connection code in the Viewer window. Do not put it on the command line.
            """;

    private static final String LEGACY_DIAGNOSTIC = "Legacy options are not supported in v2. Choose --role host on the controlled machine or --role viewer [--address ADDRESS] on the controlling machine.";

    /**
     * Parses command-line arguments.
     *
     * @throws IllegalArgumentException with a human-readable message on any error
     */
    public static LaunchOptions parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }
        for (String arg : args) {
            if ("--server".equals(arg) || "--client".equals(arg) || "--host".equals(arg)) {
                throw new IllegalArgumentException(LEGACY_DIAGNOSTIC);
            }
        }
        Set<String> seen = new HashSet<>();
        Role role = Role.CHOOSER;
        boolean help = false;
        int cmdPort = DEFAULT_CMD_PORT;
        int imgPort = DEFAULT_IMG_PORT;
        String address = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (flag == null) throw new IllegalArgumentException("Unsupported argument");
            switch (flag) {
                case "--role" -> {
                    checkNew(seen, flag);
                    role = switch (valueAt(args, ++i, flag)) {
                        case "host" -> Role.HOST;
                        case "viewer" -> Role.VIEWER;
                        default -> throw new IllegalArgumentException("Option --role expects host or viewer");
                    };
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
                case "--address" -> {
                    checkNew(seen, flag);
                    address = valueAt(args, ++i, flag).trim();
                    if (address.isEmpty()) {
                        throw new IllegalArgumentException("Option --address requires a non-empty value");
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported argument");
            }
        }

        if (role != Role.VIEWER && address != null) {
            throw new IllegalArgumentException("--address requires --role viewer");
        }
        if (cmdPort == imgPort) {
            throw new IllegalArgumentException(
                    "--cmd-port and --img-port must differ");
        }
        return new LaunchOptions(role, help, cmdPort, imgPort, address);
    }

    private static void checkNew(Set<String> seen, String flag) {
        if (!seen.add(flag)) {
            throw new IllegalArgumentException("Duplicate option: " + flag);
        }
    }

    private static String valueAt(String[] args, int index, String flag) {
        if (index >= args.length || args[index] == null || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Option " + flag + " requires a value");
        }
        return args[index];
    }

    private static int parsePort(String flag, String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Option " + flag + " expects an integer");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Option " + flag + " must be in 1..65535");
        }
        return port;
    }
}
