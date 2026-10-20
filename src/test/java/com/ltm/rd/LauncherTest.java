package com.ltm.rd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Launcher.run never calls System.exit; the GUI launcher is a counting fake. */
class LauncherTest {
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final List<LaunchOptions> launched = new ArrayList<>();

    private int run(boolean headless, String... args) {
        return Launcher.run(args, out, err, headless, options -> {
            launched.add(options);
            return Launcher.STAY_RUNNING;
        });
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpWorksInHeadless() {
        assertEquals(0, run(true, "--help"));
        assertEquals(LaunchOptions.USAGE, out());
        assertEquals(0, launched.size());
    }

    @Test
    void invalidOptionExitsTwoWithUsage() {
        assertEquals(2, run(false, "--bogus"));
        assertTrue(err().startsWith("Unknown option: --bogus"), err());
        assertTrue(err().contains(LaunchOptions.USAGE));
        assertEquals(0, launched.size());
    }

    @Test
    void serverRoleInHeadlessExitsFourWithoutGui() {
        assertEquals(4, run(true, "--server"));
        assertTrue(err().contains("graphical environment required"), err());
        assertEquals(0, launched.size());
    }

    @Test
    void chooserInHeadlessExitsFour() {
        assertEquals(4, run(true));
        assertTrue(err().contains("graphical environment required (java.awt.headless=true)"));
        assertEquals(0, launched.size());
    }

    @Test
    void clientHostIsPassedToGuiWithoutConnecting() {
        assertEquals(Launcher.STAY_RUNNING, run(false, "--client", "--host", "10.0.0.5"));
        assertEquals(1, launched.size());
        assertEquals("10.0.0.5", launched.get(0).host());
        assertEquals(LaunchOptions.Role.CLIENT, launched.get(0).role());
    }

    @Test
    void portsArePassedToGui() {
        assertEquals(Launcher.STAY_RUNNING,
                run(false, "--server", "--cmd-port", "6001", "--img-port", "6000"));
        assertEquals(6001, launched.get(0).cmdPort());
        assertEquals(6000, launched.get(0).imgPort());
    }

    @Test
    void notImplementedMessageIsGone() {
        run(false, "--server");
        run(false, "--client");
        run(false);
        run(true, "--help");
        run(true, "--server");
        assertFalse(err().contains("not implemented yet"), err());
        assertFalse(out().contains("not implemented yet"));
    }

    @Test
    void sameCmdAndImgPortStillRejected() {
        assertEquals(2, run(false, "--cmd-port", "5000", "--img-port", "5000"));
        assertEquals(0, launched.size());
    }

    @Test
    void guiStartupFailureIsReportedWithoutDetails() {
        int code = Launcher.run(new String[] {"--server"}, out, err, false, options -> {
            throw new IllegalStateException("secret detail 123456");
        });
        assertEquals(1, code);
        assertTrue(err().contains("IllegalStateException"), err());
        assertFalse(err().contains("secret detail"), "the exception text is never printed");
    }
}
