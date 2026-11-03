package com.ltm.rd.it;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Closed, secret-free diagnostics for test children; raw messages never cross this boundary. */
final class TwoJvmDiagnostics {
    enum Reason {
        UnknownPrivateCommand, InvalidRole, InvalidEvent, EventOverflow, CommandOverflow, MissingEvent, UnexpectedEof,
        HostDesktopUnavailable, HostNotListening, ViewerMustBeHeadless, ChildLifetimeExceeded,
        TargetNotPainted, TargetNotFocused, ModifiersOrUnexpectedKey, UnexpectedNativeSequence,
        HeldNativeInput, NativeFenceFailed, NativeFenceTimeout, NativeSetupFailed, NativePermissionDenied,
        NativeFenceUnsupported, NativeProbeMissing, InvalidProbeSequence, LateNativeInput,
        InvalidConnectContract, InvalidListenerPorts, ConnectNotStarted, ViewerNotActive,
        PaintedInputGateClosed, InputNotSent, RuntimeNotCreated, UncleanRuntimeShutdown,
        MonitorNotStopped, PrivateEventPipeClosed, CommandRoleMismatch, EdtUnavailable,
        OperationTimedOut, Interrupted, IoFailure, UnknownFailure
    }

    private static final Set<String> PHASES = Set.of("arguments", "startup", "desktop_paint_focus_Robot",
            "headless_viewer_classloading", "private_command", "decoded_frames", "native_input_focus",
            "actual_native_events", "shutdown", "connect", "painted_gate_input", "snapshot_monitor", "cleanup", "protocol");
    private static final Set<String> LOGGERS = Set.of("com.ltm.rd.ui.AppRuntime", "com.ltm.rd.ui.SnapshotPump",
            "com.ltm.rd.common.Ticker", "com.ltm.rd.common.SessionLifecycle", "com.ltm.rd.host.HandshakeHandler",
            "com.ltm.rd.host.HostCore", "com.ltm.rd.host.InputExecutor");
    private static final Set<String> LEVELS = Set.of("SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "OFF", "ALL");
    private static final Set<String> LOG_CODES = Set.of("TickableFailed", "WorkerFailed", "GracefulCloseFailed",
            "InterruptFailed", "SocketCloseFailed", "SessionActionFailed", "UnsupportedKey", "ServerStartFailed",
            "ConnectStartupFailed", "ShutdownFailed", "HandshakeEnded", "AcceptFailed", "ShutdownWaitEnded", "SnapshotPollFailed", "WorkerStartFailed", "Redacted");

    private TwoJvmDiagnostics() {}

    static String phase(String value) { return PHASES.contains(value) ? value : "startup"; }

    static Reason reason(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Reason fallback = Reason.UnknownFailure;
        Reason known = null;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            // Only exact fixed harness identifiers are messages we interpret, never persist arbitrary text.
            if (cause.getClass() == IllegalStateException.class || cause.getClass() == IllegalArgumentException.class) {
                try { known = Reason.valueOf(cause.getMessage()); }
                catch (IllegalArgumentException | NullPointerException ignored) { /* redact unknown message */ }
            }
            if (cause instanceof java.awt.HeadlessException) known = Reason.HostDesktopUnavailable;
            if (cause instanceof java.awt.AWTException || cause instanceof java.awt.AWTError) known = Reason.NativeSetupFailed;
            if (cause instanceof SecurityException) known = Reason.NativePermissionDenied;
            if (cause instanceof java.util.concurrent.TimeoutException) fallback = Reason.OperationTimedOut;
            if (cause instanceof InterruptedException) fallback = Reason.Interrupted;
            if (cause instanceof java.io.IOException) fallback = Reason.IoFailure;
        }
        return known == null ? fallback : known;
    }

    static boolean failureEvent(String value) {
        String[] fields = value.split(" ", -1);
        if (fields.length != 3 || !fields[0].equals("FAILURE") || !PHASES.contains(fields[1])) return false;
        try { Reason.valueOf(fields[2]); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static void failure(Consumer<String> sink, String phase, Throwable failure) {
        sink.accept("DIAG failure phase=" + phase(phase) + " reason=" + reason(failure));
        causes(sink, failure);
    }

    private static void causes(Consumer<String> sink, Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable cause = failure; cause != null && seen.add(cause) && depth < 12; cause = cause.getCause()) {
            sink.accept("DIAG cause=" + depth++ + " type=" + safeClass(cause.getClass().getName())
                    + " reason=" + reason(cause) + " message=redacted");
            int count = 0;
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (count++ == 32) break;
                sink.accept("DIAG at " + safeFrame(frame.getClassName(), frame.getMethodName()));
            }
        }
    }

    static void productLog(Consumer<String> sink, LogRecord record) {
        String logger = LOGGERS.contains(String.valueOf(record.getLoggerName())) ? record.getLoggerName() : "UnknownLogger";
        String level = LEVELS.contains(record.getLevel().getName()) ? record.getLevel().getName() : "INFO";
        String code = switch (String.valueOf(record.getMessage())) {
            case "tickable failed" -> "TickableFailed";
            case "worker failed" -> "WorkerFailed";
            case "graceful close step failed" -> "GracefulCloseFailed";
            case "interrupt failed" -> "InterruptFailed";
            case "socket close failed" -> "SocketCloseFailed";
            case "session action failed" -> "SessionActionFailed";
            case "Unsupported key skipped" -> "UnsupportedKey";
            default -> {
                String message = String.valueOf(record.getMessage());
                if (message.startsWith("server start failed: ")) yield "ServerStartFailed";
                if (message.startsWith("connect startup failed: ")) yield "ConnectStartupFailed";
                if (message.startsWith("shutdown failed: ")) yield "ShutdownFailed";
                if (message.startsWith("handshake ended: ")) yield "HandshakeEnded";
                if (message.startsWith("accept failed: ")) yield "AcceptFailed";
                if (message.startsWith("shutdown wait ended: ")) yield "ShutdownWaitEnded";
                if (message.startsWith("snapshot poll failed: ")) yield "SnapshotPollFailed";
                if (message.startsWith("cannot start worker ")) yield "WorkerStartFailed";
                yield "Redacted";
            }
        };
        sink.accept("DIAG log logger=" + logger + " level=" + level + " code=" + code + " message=redacted");
        if (record.getThrown() != null) causes(sink, record.getThrown());
    }

    /** Child-local JUL handler preserves System.Logger structure without touching product logging. */
    static Consumer<String> install(PrintStream diagnosticPipe) {
        Consumer<String> sink = line -> { diagnosticPipe.println(line); diagnosticPipe.flush(); };
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) root.removeHandler(handler);
        Handler safe = new Handler() {
            @Override public void publish(LogRecord record) { productLog(sink, record); }
            @Override public void flush() { diagnosticPipe.flush(); }
            @Override public void close() { flush(); }
        };
        safe.setLevel(java.util.logging.Level.ALL);
        root.addHandler(safe);
        System.setOut(redactedStream(sink, "stdout"));
        System.setErr(redactedStream(sink, "stderr"));
        return sink;
    }

    private static PrintStream redactedStream(Consumer<String> sink, String stream) {
        return new PrintStream(new OutputStream() {
            private boolean content;
            private int count;
            @Override public synchronized void write(int value) {
                if (value == '\n' || ++count >= 4096) {
                    sink.accept("DIAG stream=" + stream + " text=redacted");
                    content = false;
                    count = 0;
                } else content = true;
            }
            @Override public synchronized void flush() {
                if (content) {
                    sink.accept("DIAG stream=" + stream + " text=redacted");
                    content = false;
                    count = 0;
                }
            }
        }, true, StandardCharsets.UTF_8);
    }

    /** Parent validates again: even a malformed/compromised child's stderr is never a raw dump. */
    static String sanitizeLine(String line) {
        if (line.matches("DIAG stream=(?:stdout|stderr) text=redacted")) return line;
        String[] fields = line.split(" ", -1);
        try {
            if (fields.length == 4 && fields[0].equals("DIAG") && fields[1].equals("failure")
                    && fields[2].startsWith("phase=") && fields[3].startsWith("reason=")) {
                String phase = fields[2].substring(6);
                Reason reason = Reason.valueOf(fields[3].substring(7));
                if (PHASES.contains(phase)) return "DIAG failure phase=" + phase + " reason=" + reason;
            }
            if (fields.length == 5 && fields[0].equals("DIAG") && fields[1].matches("cause=(?:[0-9]|1[01])")
                    && fields[2].startsWith("type=") && fields[3].startsWith("reason=") && fields[4].equals("message=redacted")) {
                return "DIAG " + fields[1] + " type=" + safeClass(fields[2].substring(5))
                        + " reason=" + Reason.valueOf(fields[3].substring(7)) + " message=redacted";
            }
            if (fields.length == 3 && fields[0].equals("DIAG") && fields[1].equals("at")) {
                String frame = fields[2];
                int open = frame.indexOf('(');
                int dot = open < 0 ? -1 : frame.lastIndexOf('.', open);
                if (dot > 0) return "DIAG at " + safeFrame(frame.substring(0, dot), frame.substring(dot + 1, open));
            }
            if (fields.length == 6 && fields[0].equals("DIAG") && fields[1].equals("log")
                    && fields[2].startsWith("logger=") && fields[3].startsWith("level=")
                    && fields[4].startsWith("code=") && fields[5].equals("message=redacted")) {
                String logger = fields[2].substring(7), level = fields[3].substring(6), code = fields[4].substring(5);
                return "DIAG log logger=" + (LOGGERS.contains(logger) ? logger : "UnknownLogger")
                        + " level=" + (LEVELS.contains(level) ? level : "INFO") + " code="
                        + (LOG_CODES.contains(code) ? code : "Redacted") + " message=redacted";
            }
        } catch (IllegalArgumentException ignored) { /* only closed schema survives */ }
        return "DIAG stream=stderr text=redacted";
    }

    private static Class<?> trustedClass(String name) throws ClassNotFoundException {
        if (!name.startsWith("com.ltm.rd.") && !name.startsWith("java.") && !name.startsWith("javax.swing.")
                && !name.startsWith("sun.awt.")) throw new ClassNotFoundException();
        return Class.forName(name, false, TwoJvmDiagnostics.class.getClassLoader());
    }

    private static String safeClass(String name) {
        try { return trustedClass(name).getName(); }
        catch (ClassNotFoundException | LinkageError ignored) { return "UnknownType"; }
    }

    private static String safeFrame(String className, String methodName) {
        try {
            Class<?> type = trustedClass(className);
            boolean known = methodName.equals("<init>") || methodName.equals("<clinit>");
            for (var method : type.getDeclaredMethods()) known |= method.getName().equals(methodName);
            if (!known) return "redacted";
            // No raw file names or numeric line data from mutable StackTraceElements is persisted.
            return type.getName() + "." + methodName + "(" + type.getNestHost().getSimpleName() + ".java)";
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) { return "redacted"; }
    }
}
