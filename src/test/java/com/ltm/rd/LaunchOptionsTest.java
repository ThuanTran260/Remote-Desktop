package com.ltm.rd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LaunchOptionsTest {

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
        assertNull(o.host());
    }

    @Test
    void serverFlag() {
        assertEquals(LaunchOptions.Role.SERVER, parse("--server").role());
    }

    @Test
    void clientFlag() {
        assertEquals(LaunchOptions.Role.CLIENT, parse("--client").role());
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
        LaunchOptions o = parse("--client", "--host", "192.168.1.5");
        assertEquals("192.168.1.5", o.host());
    }

    @Test
    void hostWithoutRoleAllowed() {
        assertEquals("10.0.0.1", parse("--host", "10.0.0.1").host());
    }

    @Test
    void bothRolesRejected() {
        assertRejected("--server", "--client");
        assertRejected("--client", "--server");
    }

    @Test
    void hostWithServerRejected() {
        assertRejected("--server", "--host", "1.2.3.4");
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
        assertRejected("--client", "--host");
        assertRejected("--cmd-port", "--server");
    }

    @Test
    void duplicatesRejected() {
        assertRejected("--server", "--server");
        assertRejected("--help", "--help");
        assertRejected("--cmd-port", "6001", "--cmd-port", "6002");
        assertRejected("--img-port", "6001", "--img-port", "6002");
        assertRejected("--client", "--host", "1.1.1.1", "--host", "2.2.2.2");
    }

    @Test
    void nullArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(null));
    }

    @Test
    void usageDescribesRolesCorrectly() {
        String serverLine = usageLine("--server");
        String clientLine = usageLine("--client");
        assertTrue(serverLine.contains("controlling machine"), serverLine);
        assertTrue(clientLine.contains("controlled machine"), clientLine);
    }

    private static String usageLine(String flag) {
        return LaunchOptions.USAGE.lines()
                .filter(l -> l.stripLeading().startsWith(flag + " "))
                .findFirst()
                .orElseThrow();
    }
}
