package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

/** Opt-in native desktop gate. Its parent JVM remains headless. */
@Tag("desktop")
@Timeout(120)
class TwoJvmIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void transfersThirtyFramesAndOneHundredOrderedInputEvents(TestInfo info) throws Exception {
        Path evidence = evidence(info);
        try (TwoJvmPeer host = TwoJvmPeer.start("host", evidence);
                TwoJvmPeer viewer = TwoJvmPeer.start("viewer", evidence)) {
            connect(host, viewer);
            viewer.send("FRAMES");
            String[] frames = viewer.awaitEvent("FRAMES", WAIT).split(" ");
            assertTrue(Long.parseLong(frames[1]) >= 30, "complete decoded frames");
            assertTrue(Integer.parseInt(frames[2]) > 0 && Integer.parseInt(frames[3]) > 0);
            host.send("ARM_INPUT");
            assertEquals("FOCUSED", host.awaitEvent("FOCUSED", WAIT));
            viewer.send("INPUT");
            assertEquals("INPUT_SENT 100", viewer.awaitEvent("INPUT_SENT", WAIT));
            host.send("EVENTS");
            List<String> expected = new ArrayList<>();
            for (int pair = 0; pair < 50; pair++) {
                int key = pair % 2 == 0 ? 65 : 66;
                expected.add("P" + key);
                expected.add("R" + key);
            }
            assertEquals("INPUT_EVENTS " + String.join(",", expected), host.awaitEvent("INPUT_EVENTS", WAIT));
            shutdown(viewer, false);
            assertClosed(host, "host", WAIT);
            shutdown(host, false);
        }
    }

    @Test
    void hostShutdownClosesViewer(TestInfo info) throws Exception {
        graceful(info, "host");
    }

    @Test
    void viewerShutdownClosesHost(TestInfo info) throws Exception {
        graceful(info, "viewer");
    }

    @Test
    void killedHostClosesViewerWithinFifteenSeconds(TestInfo info) throws Exception {
        killed(info, "host");
    }

    @Test
    void killedViewerClosesHostWithinFifteenSeconds(TestInfo info) throws Exception {
        killed(info, "viewer");
    }

    private static void graceful(TestInfo info, String role) throws Exception {
        Path evidence = evidence(info);
        try (TwoJvmPeer host = TwoJvmPeer.start("host", evidence);
                TwoJvmPeer viewer = TwoJvmPeer.start("viewer", evidence)) {
            connect(host, viewer);
            shutdown(role.equals("host") ? host : viewer, role.equals("host"));
            assertClosed(role.equals("host") ? viewer : host, role.equals("host") ? "viewer" : "host", WAIT);
            shutdown(role.equals("host") ? viewer : host, false);
        }
    }

    private static void killed(TestInfo info, String role) throws Exception {
        Path evidence = evidence(info);
        try (TwoJvmPeer host = TwoJvmPeer.start("host", evidence);
                TwoJvmPeer viewer = TwoJvmPeer.start("viewer", evidence)) {
            connect(host, viewer);
            TwoJvmPeer victim = role.equals("host") ? host : viewer;
            TwoJvmPeer survivor = role.equals("host") ? viewer : host;
            long start = System.nanoTime();
            victim.kill();
            assertClosed(survivor, role.equals("host") ? "viewer" : "host", Duration.ofSeconds(15));
            long elapsed = System.nanoTime() - start;
            Files.writeString(evidence.resolve("kill-elapsed-nanos.txt"), Long.toString(elapsed));
            assertTrue(elapsed <= Duration.ofSeconds(15).toNanos(), "peer session closed within 15 seconds of kill");
            shutdown(survivor, false);
        }
    }

    private static void connect(TwoJvmPeer host, TwoJvmPeer viewer) throws Exception {
        assertTrue(Boolean.getBoolean("java.awt.headless"), "JUnit parent must stay headless");
        assertNotEquals(ProcessHandle.current().pid(), host.processHandle().pid());
        assertNotEquals(ProcessHandle.current().pid(), viewer.processHandle().pid());
        assertNotEquals(host.processHandle().pid(), viewer.processHandle().pid());
        String ready = host.awaitEvent("READY", WAIT); // Private pipe: never assert/print the PIN-bearing line.
        String[] fields = ready.split(" ");
        if (fields.length != 5 || !fields[1].equals("host") || !fields[4].matches("[0-9]{6}")) {
            throw new AssertionError("Invalid private Host readiness contract (payload redacted)");
        }
        assertEquals("READY viewer", viewer.awaitEvent("READY", WAIT));
        viewer.send("CONNECT " + fields[2] + " " + fields[3] + " " + fields[4]);
        assertEquals("ACTIVE", host.awaitEvent("ACTIVE", WAIT));
        assertEquals("ACTIVE", viewer.awaitEvent("ACTIVE", WAIT));
    }

    private static void shutdown(TwoJvmPeer peer, boolean activeHost) throws Exception {
        peer.send("SHUTDOWN");
        assertEquals("SHUTDOWN true true " + (activeHost ? "RELEASED" : "NOT_NEEDED"), peer.awaitEvent("SHUTDOWN", WAIT));
    }

    private static void assertClosed(TwoJvmPeer peer, String role, Duration timeout) throws Exception {
        assertEquals("CLOSED " + (role.equals("host") ? "LISTENING" : "IDLE") + " NONE",
                peer.awaitEvent("CLOSED", timeout));
    }

    private static Path evidence(TestInfo info) throws Exception {
        Path root = Path.of(System.getProperty("twoJvm.evidenceDirectory", "target/two-jvm-evidence"));
        return Files.createTempDirectory(Files.createDirectories(root), info.getTestMethod().orElseThrow().getName() + "-");
    }
}
