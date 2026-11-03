package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

/** Headless process/pipe checks; none starts a Host or invokes Robot. */
@Timeout(30)
class TwoJvmPeerTest {
    private Path evidence;

    @BeforeEach
    void createEvidenceDirectory(TestInfo info) throws Exception {
        Path root = Path.of(System.getProperty("twoJvm.evidenceDirectory", "target/two-jvm-headless-evidence"));
        evidence = Files.createTempDirectory(Files.createDirectories(root), info.getTestMethod().orElseThrow().getName() + "-");
    }

    @Test
    void viewerChildLoadsTestClasspathAndIsReaped() throws Exception {
        ProcessHandle child;
        try (TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence)) {
            child = peer.processHandle();
            assertNotEquals(ProcessHandle.current().pid(), child.pid());
            assertEquals("READY viewer", peer.awaitEvent("READY", Duration.ofSeconds(10)));
        }
        assertFalse(child.isAlive());
        String log = Files.readString(evidence.resolve("viewer-events.txt"));
        assertTrue(log.contains("READY") && log.contains("CLEANUP alive=false"));
    }

    @Test
    void missingEventHasABoundedDiagnosticAndStillReapsChild() throws Exception {
        ProcessHandle child;
        try (TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence)) {
            child = peer.processHandle();
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            long start = System.nanoTime();
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> peer.awaitEvent("FRAMES", Duration.ofMillis(50)));
            assertTrue(failure.getMessage().contains("FRAMES"));
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
        }
        assertFalse(child.isAlive());
    }

    @Test
    void invalidPrivateCommandFailsWithoutPersistingItsPayload() throws Exception {
        String secret = "sensitive-private-fixture";
        try (TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence)) {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID " + secret);
            assertThrows(AssertionError.class, () -> peer.awaitEvent("ACTIVE", Duration.ofSeconds(10)));
        }
        try (var files = Files.list(evidence)) {
            for (Path path : files.toList()) assertFalse(Files.readString(path).contains(secret));
        }
    }

    @Test
    void unobservedChildFailureCannotPassCleanup() throws Exception {
        TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence);
        try {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID");
            peer.processHandle().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(AssertionError.class, peer::close, "late child failure must fail the gate too");
        } finally {
            peer.close();
        }
    }
}
