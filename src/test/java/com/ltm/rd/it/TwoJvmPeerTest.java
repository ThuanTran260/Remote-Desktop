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
            TwoJvmPeer.EventTimeout failure = assertThrows(TwoJvmPeer.EventTimeout.class,
                    () -> peer.awaitEvent("FRAMES", Duration.ofMillis(50)));
            assertTrue(failure.getMessage().contains("FRAMES"));
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
        }
        assertFalse(child.isAlive());
    }

    @Test
    void invalidPrivateCommandFailsWithoutPersistingItsPayload() throws Exception {
        String secret = "sensitive-private-fixture PIN=947261 token=deadbeefcafebabefeedface01234567";
        try (TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence)) {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID " + secret);
            assertInvalidCommandFailure(peer, Duration.ofSeconds(10));
            assertFalse(peer.processHandle().isAlive());
        }
        String diagnostics = Files.readString(evidence.resolve("viewer-diagnostics.txt"));
        assertTrue(diagnostics.contains("phase=private_command reason=UnknownPrivateCommand"));
        assertTrue(diagnostics.contains("cause=0 type=java.lang.IllegalArgumentException"));
        assertTrue(diagnostics.contains("at com.ltm.rd.it.TwoJvmPeerMain.run("));
        assertTrue(Files.readString(evidence.resolve("viewer-events.txt")).contains("exit=1"));
        try (var files = Files.list(evidence)) {
            for (Path path : files.toList()) {
                String persisted = Files.readString(path);
                for (String fixture : secret.split(" ")) {
                    assertFalse(persisted.contains(fixture.replace("PIN=", "").replace("token=", "")));
                }
            }
        }
    }

    @Test
    void invalidCommandRetainsSafeDiagnosticArtifact() throws Exception {
        try (TwoJvmPeer peer = TwoJvmPeer.start("viewer", evidence)) {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID sensitive-private-fixture");
            assertThrows(AssertionError.class, () -> peer.awaitEvent("ACTIVE", Duration.ofSeconds(10)));
            peer.processHandle().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(Files.exists(evidence.resolve("viewer-diagnostics.txt")),
                "the causal diagnostic must survive child termination");
        String diagnostic = Files.readString(evidence.resolve("viewer-diagnostics.txt"));
        assertTrue(diagnostic.contains("reason=UnknownPrivateCommand"));
        assertTrue(diagnostic.contains("cause=0 type=java.lang.IllegalArgumentException"));
        assertTrue(diagnostic.contains("at com.ltm.rd.it.TwoJvmPeerMain.run("));
        assertFalse(diagnostic.contains("sensitive-private-fixture"));
    }

    @Test
    void ignoredInvalidCommandCannotSatisfyChildFailureAssertion() throws Exception {
        ProcessHandle child;
        try (TwoJvmPeer peer = probe(false)) {
            child = peer.processHandle();
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID sensitive-private-fixture");
            AssertionError rejected = assertThrows(AssertionError.class,
                    () -> assertInvalidCommandFailure(peer, Duration.ofMillis(50)));
            assertInstanceOf(TwoJvmPeer.EventTimeout.class, rejected.getCause());
            assertTrue(child.isAlive(), "ignored command child is still running at rejection");
        }
        assertFalse(child.isAlive());
        assertTrue(Files.readString(evidence.resolve("viewer-events.txt")).contains("exit=0"));
    }

    @Test
    void unexpectedEofCannotSatisfyChildFailureAssertion() throws Exception {
        try (TwoJvmPeer peer = probe(true)) {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
            peer.send("INVALID sensitive-private-fixture");
            AssertionError rejected = assertThrows(AssertionError.class,
                    () -> assertInvalidCommandFailure(peer, Duration.ofSeconds(10)));
            assertInstanceOf(TwoJvmPeer.UnexpectedEof.class, rejected.getCause());
        }
    }

    @Test
    void childProductLoggingAndRawStreamsAreTraceableWithoutPersistedSecrets() throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Djava.awt.headless=true", "-cp",
                System.getProperty("surefire.test.class.path"), DiagnosticProcess.class.getName()).start();
        try (TwoJvmPeer peer = new TwoJvmPeer("viewer", process, evidence)) {
            peer.awaitEvent("READY", Duration.ofSeconds(10));
        }
        String diagnostic = Files.readString(evidence.resolve("viewer-diagnostics.txt"));
        assertTrue(diagnostic.contains("logger=com.ltm.rd.common.SessionLifecycle level=WARNING code=WorkerFailed"));
        assertTrue(diagnostic.contains("cause=0 type=java.util.concurrent.ExecutionException"));
        assertTrue(diagnostic.contains("cause=1 type=java.lang.IllegalStateException"));
        assertTrue(diagnostic.contains("TwoJvmPeerTest$DiagnosticProcess.main("));
        assertTrue(diagnostic.contains("stream=stdout text=redacted"));
        assertTrue(diagnostic.contains("stream=stderr text=redacted"));
        assertTrue(diagnostic.contains("logger=com.ltm.rd.common.Ticker level=WARNING code=TickableFailed"),
                "diagnostics produced during STOP/exit must be drained before evidence closes");
        try (var files = Files.list(evidence)) {
            for (Path path : files.toList()) {
                String persisted = Files.readString(path);
                for (String secret : java.util.List.of("sensitive-private-fixture", "947261", "deadbeefcafebabefeedface01234567")) {
                    assertFalse(persisted.contains(secret));
                }
            }
        }
    }

    public static final class DiagnosticProcess {
        public static void main(String[] args) throws Exception {
            java.io.PrintStream privateWire = System.out;
            TwoJvmDiagnostics.install(System.err);
            String secret = "sensitive-private-fixture PIN=947261 token=deadbeefcafebabefeedface01234567";
            System.out.println(secret);
            System.err.println("CONNECT 5001 5000 " + secret);
            System.getLogger("com.ltm.rd.common.SessionLifecycle").log(System.Logger.Level.WARNING,
                    "worker failed", new java.util.concurrent.ExecutionException(new IllegalStateException(secret)));
            privateWire.println("READY viewer");
            if (!"STOP".equals(probeCommands().poll(10, java.util.concurrent.TimeUnit.SECONDS))) {
                throw new IllegalStateException("Unexpected probe command");
            }
            System.getLogger("com.ltm.rd.common.Ticker").log(System.Logger.Level.WARNING, "tickable failed");
        }
    }

    private static void assertInvalidCommandFailure(TwoJvmPeer peer, Duration timeout) throws Exception {
        TwoJvmPeer.ChildFailure failure = assertThrows(TwoJvmPeer.ChildFailure.class,
                () -> peer.awaitEvent("ACTIVE", timeout));
        assertEquals("private_command", failure.phase);
        assertEquals("UnknownPrivateCommand", failure.reason);
        assertEquals(1, peer.awaitFailureExit(Duration.ofSeconds(10)));
    }

    private TwoJvmPeer probe(boolean eof) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Djava.awt.headless=true", "-cp", classpath,
                IgnoringCommandProcess.class.getName(), Boolean.toString(eof)).start();
        return new TwoJvmPeer("viewer", process, evidence);
    }

    /** Adversarial pipe process only; it has no Host execution or public helper child role. */
    public static final class IgnoringCommandProcess {
        public static void main(String[] args) throws Exception {
            System.out.println("READY viewer");
            var commands = probeCommands();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (true) {
                long remaining = deadline - System.nanoTime();
                String command = remaining <= 0 ? null : commands.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (command == null) throw new IllegalStateException("ProbeLifetimeExceeded");
                if (command.equals("STOP") || Boolean.parseBoolean(args[0])) return;
                // Deliberately ignore invalid command. No product or native input is involved.
            }
        }
    }

    private static java.util.concurrent.BlockingQueue<String> probeCommands() {
        var commands = new java.util.concurrent.ArrayBlockingQueue<String>(4);
        Thread reader = new Thread(() -> TwoJvmPeer.pump(System.in, commands::offer, () -> commands.offer("STOP")),
                "two-jvm-adversarial-probe-reader");
        reader.setDaemon(true);
        reader.start();
        return commands;
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
