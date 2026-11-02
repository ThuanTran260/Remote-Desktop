package com.ltm.rd;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LaunchOptionsTest {

    private record CliCase(String[] args, String role, String address, boolean help, int cmd, int img) { }

    static Stream<CliCase> v2Cases() {
        return Stream.of(
                new CliCase(new String[] {}, "CHOOSER", null, false, 5001, 5000),
                new CliCase(new String[] {"--cmd-port", "6001", "--img-port", "6000"}, "CHOOSER", null, false, 6001, 6000),
                new CliCase(new String[] {"--role", "host"}, "HOST", null, false, 5001, 5000),
                new CliCase(new String[] {"--role", "viewer"}, "VIEWER", null, false, 5001, 5000),
                new CliCase(new String[] {"--address", " 192.168.1.20 ", "--role", "viewer"}, "VIEWER", "192.168.1.20", false, 5001, 5000),
                new CliCase(new String[] {"--help", "--role", "viewer", "--address", "192.168.1.20"}, "VIEWER", "192.168.1.20", true, 5001, 5000));
    }

    @ParameterizedTest @MethodSource("v2Cases")
    void v2RoleAddressAndPortsMatrix(CliCase c) {
        LaunchOptions actual = assertDoesNotThrow(() -> parse(c.args()));
        assertEquals(c.role(), actual.role().name());
        assertEquals(c.address(), actual.address());
        assertEquals(c.help(), actual.help());
        assertEquals(c.cmd(), actual.cmdPort());
        assertEquals(c.img(), actual.imgPort());
    }

    static Stream<Arguments> invalidV2Cases() {
        return Stream.of(
                new String[] {"--role", "host", "--address", "X"}, new String[] {"--address", "X"},
                new String[] {"--role", "host", "--role", "host"}, new String[] {"--role", "host", "--role", "viewer"},
                new String[] {"--help", "--help"}, new String[] {"--role"}, new String[] {"--role", "HOST"},
                new String[] {"--role=host"}, new String[] {"--role", "server"},
                new String[] {"--role", "viewer", "--address"}, new String[] {"--role", "viewer", "--address", " "},
                new String[] {"--role", "viewer", "--address", "X", "--address", "X"},
                new String[] {"--cmd-port", "0"}, new String[] {"--img-port", "65536"},
                new String[] {"--cmd-port", "abc"}, new String[] {"--cmd-port", "5000"},
                new String[] {"--cmd-port", "6001", "--img-port", "6001"},
                new String[] {"--pin", "123456"}, new String[] {"--code", "123456"},
                new String[] {"positional"}, new String[] {"--unknown"},
                new String[] {"--cmd-port", "6001", "--cmd-port", "6001"},
                new String[] {"--img-port", "6000", "--img-port", "6000"})
                .map(args -> Arguments.of((Object) args));
    }

    @ParameterizedTest @MethodSource("invalidV2Cases")
    void invalidV2ArgumentsRemainInvalidWithHelp(String[] args) {
        assertRejected(args);
        String[] withHelp = new String[args.length + 1];
        withHelp[0] = "--help";
        System.arraycopy(args, 0, withHelp, 1, args.length);
        assertRejected(withHelp);
    }

    @Test void legacyAnywhereWinsWithoutEchoingValues() {
        String expected = "Legacy options are not supported in v2. Choose --role host on the controlled machine or --role viewer [--address ADDRESS] on the controlling machine.";
        for (String legacy : new String[] {"--server", "--client", "--host"}) {
            for (String[] args : new String[][] {
                    {legacy}, {"--bogus-secret123456", legacy}, {"--help", "--role", "viewer", legacy, "secret123456"},
                    {"--cmd-port", "secret123456", legacy}, {"--role", "secret123456", legacy}}) {
                var error = assertThrows(IllegalArgumentException.class, () -> parse(args));
                assertEquals(expected, error.getMessage());
                assertFalse(error.getMessage().contains("secret123456"));
            }
        }
    }

    @Test void diagnosticsNeverEchoUntrustedValues() {
        for (String[] args : new String[][] {{"--cmd-port", "secret123456"}, {"--bogus-secret123456"},
                {"--role", "secret123456"}, {"secret123456"}, {"--code", "secret123456"}}) {
            var error = assertThrows(IllegalArgumentException.class, () -> parse(args));
            assertFalse(error.getMessage().contains("secret123456"));
        }
    }

    private static LaunchOptions parse(String... args) {
        return LaunchOptions.parse(args);
    }

    private static void assertRejected(String... args) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse(args));
        assertFalse(e.getMessage() == null || e.getMessage().isBlank());
    }

    @Test
    void defaultsWithNoArgsIsChooser() {
        LaunchOptions o = parse();
        assertEquals(LaunchOptions.Role.CHOOSER, o.role());
        assertFalse(o.help());
        assertEquals(5001, o.cmdPort());
        assertEquals(5000, o.imgPort());
        assertNull(o.address());
    }

    @Test
    void serverFlag() {
        assertEquals(LaunchOptions.Role.HOST, parse("--role", "host").role());
    }

    @Test
    void clientFlag() {
        assertEquals(LaunchOptions.Role.VIEWER, parse("--role", "viewer").role());
    }

    @Test
    void helpFlag() {
        assertTrue(parse("--help").help());
    }

    @Test
    void cmdPortFlag() {
        assertEquals(6001, parse("--cmd-port", "6001").cmdPort());
    }

    @Test
    void imgPortFlag() {
        assertEquals(6000, parse("--img-port", "6000").imgPort());
    }

    @Test
    void hostWithClient() {
        LaunchOptions o = parse("--role", "viewer", "--address", "192.168.1.5");
        assertEquals("192.168.1.5", o.address());
    }

    @Test
    void hostWithoutRoleAllowed() {
        assertRejected("--address", "10.0.0.1");
    }

    @Test
    void bothRolesRejected() {
        assertRejected("--role", "host", "--role", "viewer");
        assertRejected("--role", "viewer", "--role", "host");
    }

    @Test
    void hostWithServerRejected() {
        assertRejected("--role", "host", "--address", "1.2.3.4");
    }

    @Test
    void portBounds() {
        assertRejected("--cmd-port", "0");
        assertRejected("--cmd-port", "65536");
        assertRejected("--img-port", "0");
        assertRejected("--img-port", "65536");
        assertEquals(1, parse("--cmd-port", "1").cmdPort());
        assertEquals(65535, parse("--cmd-port", "65535").cmdPort());
        assertEquals(1, parse("--img-port", "1").imgPort());
        assertEquals(65535, parse("--img-port", "65535").imgPort());
    }

    @Test
    void nonNumericPortRejected() {
        assertRejected("--cmd-port", "abc");
        assertRejected("--img-port", "");
    }

    @Test
    void equalPortsRejected() {
        assertRejected("--cmd-port", "7000", "--img-port", "7000");
        assertRejected("--cmd-port", "5000");
        assertRejected("--img-port", "5001");
    }

    @Test
    void unknownFlagRejected() {
        assertRejected("--bogus");
        assertRejected("positional");
    }

    @Test
    void missingValueRejected() {
        assertRejected("--cmd-port");
        assertRejected("--img-port");
        assertRejected("--role", "viewer", "--address");
        assertRejected("--cmd-port", "--role", "host");
    }

    @Test
    void duplicatesRejected() {
        assertRejected("--role", "host", "--role", "host");
        assertRejected("--help", "--help");
        assertRejected("--cmd-port", "6001", "--cmd-port", "6002");
        assertRejected("--img-port", "6001", "--img-port", "6002");
        assertRejected("--role", "viewer", "--address", "1.1.1.1", "--address", "2.2.2.2");
    }

    @Test
    void nullArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(null));
    }

    @Test
    void usageDescribesRolesCorrectly() {
        String serverLine = usageLine("--role host");
        String clientLine = usageLine("--role viewer");
        assertTrue(serverLine.contains("controlled machine"), serverLine);
        assertTrue(clientLine.contains("controlling machine"), clientLine);
    }

    private static String usageLine(String flag) {
        return LaunchOptions.USAGE.lines()
                .filter(l -> l.stripLeading().startsWith(flag + " "))
                .findFirst()
                .orElseThrow();
    }
}
