package com.ltm.rd.it;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns one test-only child. Raw private pipes and their payloads are never persisted. */
final class TwoJvmPeer implements AutoCloseable {
    private final String role;
    private final Process process;
    private final Path eventsFile;
    private final Path stderrFile;
    private final BufferedWriter commands;
    private final BlockingQueue<String> events = new ArrayBlockingQueue<>(64);
    private final Deque<String> pending = new ArrayDeque<>();
    private final ExecutorService io = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "two-jvm-private-pipe");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;
    private volatile boolean childFailed;
    private boolean failureObserved;
    private boolean expectedForcedExit;

    static TwoJvmPeer start(String role, Path evidenceDirectory) throws IOException {
        if (!role.equals("host") && !role.equals("viewer")) throw new IllegalArgumentException("Invalid child role");
        Files.createDirectories(evidenceDirectory);
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null) classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) throw new IllegalStateException("Missing child test/runtime classpath");
        // ProcessBuilder owns argv quoting. Neither argv nor environment carries an authentication code.
        Process process = new ProcessBuilder(java.toString(), "-Djava.awt.headless=" + !role.equals("host"),
                "-cp", classpath, TwoJvmPeerMain.class.getName(), "--child-role", role).start();
        try {
            return new TwoJvmPeer(role, process, evidenceDirectory);
        } catch (IOException | RuntimeException failure) {
            process.destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) failure.addSuppressed(new IOException("Child not reaped"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
            throw failure;
        }
    }

    private TwoJvmPeer(String role, Process process, Path evidence) throws IOException {
        this.role = role;
        this.process = process;
        eventsFile = evidence.resolve(role + "-events.txt");
        stderrFile = evidence.resolve(role + "-stderr-redacted.txt");
        Files.writeString(eventsFile, "PID " + process.pid() + " parent=" + ProcessHandle.current().pid() + "\n",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(stderrFile, "Only stderr line counts are retained; all raw text is discarded.\n",
                StandardOpenOption.CREATE_NEW);
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        io.submit(() -> pump(process.getInputStream(), this::receive, () -> offer("EOF")));
        io.submit(() -> pump(process.getErrorStream(), line -> append(stderrFile, "STDERR_LINE redacted\n"), () -> {}));
    }

    void send(String command) throws Exception {
        if (closed) throw new IllegalStateException("Peer already closed");
        if (command.length() > 1024 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid private command framing");
        }
        // Writes are bounded too: a wedged child's stdin must not wedge the JUnit owner.
        io.submit(() -> {
            commands.write(command);
            commands.newLine();
            commands.flush();
            return null;
        }).get(2, TimeUnit.SECONDS);
        append(eventsFile, "COMMAND " + commandKind(command) + " payload=omitted\n");
    }

    String awaitEvent(String kind, Duration timeout) throws InterruptedException {
        if (!kind.matches("[A-Z_]+") || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid event wait");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (var iterator = pending.iterator(); iterator.hasNext();) {
            String event = iterator.next();
            if (commandKind(event).equals(kind)) {
                iterator.remove();
                return event;
            }
        }
        while (true) {
            long remaining = deadline - System.nanoTime();
            String event = remaining <= 0 ? null : events.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) throw new AssertionError(role + " timed out awaiting " + kind + "; alive=" + process.isAlive());
            String received = commandKind(event);
            if (received.equals("FAILURE") || received.equals("EOF")) {
                failureObserved = true;
                throw new AssertionError(role + " child failed while awaiting " + kind
                        + "; environment/phase diagnostic=" + (received.equals("FAILURE") ? event : "pipe closed")
                        + "; see sanitized evidence");
            }
            if (received.equals(kind)) return event;
            if (pending.size() >= 64) throw new AssertionError("Unexpected private event backlog");
            pending.addLast(event);
        }
    }

    ProcessHandle processHandle() {
        return process.toHandle();
    }

    void kill() {
        append(eventsFile, "KILL force=true\n");
        if (!process.toHandle().destroyForcibly()) throw new AssertionError("Owned child kill was not accepted");
        expectedForcedExit = true;
    }

    private void receive(String line) {
        boolean valid = switch (commandKind(line)) {
            case "READY" -> line.equals("READY viewer") || line.matches("READY host [0-9]{1,5} [0-9]{1,5} [0-9]{6}");
            case "ACTIVE", "FOCUSED" -> line.equals(commandKind(line));
            case "FRAMES" -> line.matches("FRAMES [0-9]+ [0-9]+ [0-9]+");
            case "INPUT_SENT" -> line.equals("INPUT_SENT 100");
            case "INPUT_EVENTS" -> line.matches("INPUT_EVENTS (?:[PR](?:65|66))(?:,[PR](?:65|66))*");
            case "CLOSED" -> line.matches("CLOSED (?:LISTENING|IDLE) NONE");
            case "SHUTDOWN" -> line.matches("SHUTDOWN (?:true|false) (?:true|false) (?:RELEASED|NOT_NEEDED|FAILED|UNCONFIRMED)");
            case "FAILURE" -> line.matches("FAILURE [A-Za-z_]+ [A-Za-z_]+");
            default -> false;
        };
        if (!valid) {
            childFailed = true;
            offer("FAILURE protocol InvalidEvent");
            return;
        }
        if (line.startsWith("FAILURE ")) childFailed = true;
        // READY contains the only PIN. All other event forms have closed, non-secret schemas.
        append(eventsFile, "EVENT " + (line.startsWith("READY") ? "READY payload=omitted" : line) + "\n");
        offer(line);
    }

    private void offer(String event) {
        if (!events.offer(event)) {
            events.clear();
            events.offer("FAILURE protocol EventOverflow");
        }
    }

    /** Dedicated pipe readers may block in the OS; their owner closes/reaps the child with a bound. */
    static void pump(InputStream stream, Consumer<String> lineConsumer, Runnable eof) {
        try (stream) {
            StringBuilder line = new StringBuilder();
            int value;
            while ((value = stream.read()) != -1) {
                if (value == '\n') {
                    lineConsumer.accept(line.toString());
                    line.setLength(0);
                } else if (value != '\r') {
                    if (line.length() >= 4096) throw new IOException("Private pipe line limit");
                    line.append((char) value); // The private wire alphabet is ASCII.
                }
            }
            if (!line.isEmpty()) throw new IOException("Truncated private pipe line");
        } catch (IOException failure) {
            // No raw exception message or raw child text escapes the pipe boundary.
        } finally {
            eof.run();
        }
    }

    private static String commandKind(String command) {
        int space = command.indexOf(' ');
        String kind = space < 0 ? command : command.substring(0, space);
        return kind.matches("[A-Z_]+") ? kind : "INVALID";
    }

    private static synchronized void append(Path path, String safeText) {
        try {
            Files.writeString(path, safeText, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist sanitized child evidence", failure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        List<ProcessHandle> owned = new ArrayList<>(process.descendants().toList());
        owned.add(process.toHandle());
        boolean interrupted = false;
        try {
            if (process.isAlive()) {
                try { send("STOP"); } catch (Exception failure) { append(eventsFile, "STOP delivery=failed\n"); }
                try {
                    if (!process.waitFor(7, TimeUnit.SECONDS)) append(eventsFile, "CLEANUP escalation=force\n");
                } catch (InterruptedException failure) { interrupted = true; }
            }
        } finally {
            closed = true;
            // Only descendants of this child are owned; never enumerate unrelated Java processes.
            owned.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            for (ProcessHandle child : owned) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (child.isAlive() && remaining > 0) child.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) { interrupted = true; }
                catch (Exception failure) { append(eventsFile, "CLEANUP reap=failed\n"); }
            }
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException failure) { interrupted = true; }
            try { process.getOutputStream().close(); } catch (IOException ignored) { /* child was reaped */ }
            try { process.getInputStream().close(); } catch (IOException ignored) { /* child was reaped */ }
            try { process.getErrorStream().close(); } catch (IOException ignored) { /* child was reaped */ }
            io.shutdownNow();
            boolean ioStopped = false;
            try { ioStopped = io.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { interrupted = true; }
            boolean alive = owned.stream().anyMatch(ProcessHandle::isAlive);
            append(eventsFile, "CLEANUP alive=" + alive + " ioStopped=" + ioStopped
                    + " exit=" + (process.isAlive() ? "running" : process.exitValue()) + "\n");
            if (interrupted) Thread.currentThread().interrupt();
            if (alive || !ioStopped) throw new AssertionError("Owned child/pipe cleanup incomplete; see sanitized evidence");
            if (!failureObserved && !expectedForcedExit && (childFailed || process.exitValue() != 0)) {
                throw new AssertionError("Unobserved child failure/unclean exit; see sanitized evidence");
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
