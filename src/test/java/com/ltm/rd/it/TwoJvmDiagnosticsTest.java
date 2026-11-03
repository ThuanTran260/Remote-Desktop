package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TwoJvmDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void nestedConcreteReasonsSurviveWrappers() {
        for (String reason : List.of("TargetNotPainted", "TargetNotFocused", "HostNotListening", "NativeSetupFailed")) {
            Throwable failure = new ExecutionException(new IllegalStateException("EdtUnavailable",
                    new IllegalStateException(reason)));
            assertEquals(reason, TwoJvmDiagnostics.reason(failure).name());
            List<String> lines = new ArrayList<>();
            TwoJvmDiagnostics.failure(lines::add, "desktop_paint_focus_Robot", failure);
            assertTrue(lines.stream().anyMatch(line -> line.contains("cause=2") && line.contains("reason=" + reason)));
            assertTrue(lines.stream().anyMatch(line -> line.contains("TwoJvmDiagnosticsTest.nestedConcreteReasonsSurviveWrappers(")));
        }
    }

    @Test
    void productLogAndCauseStructureSurviveWithoutSecretsOrForgedMetadata() throws Exception {
        List<String> secrets = List.of("sensitive-private-fixture", "947261", "token=deadbeefcafebabefeedface01234567",
                "CONNECT 5001 5000 947261", "READY host 5001 5000 947261");
        IllegalStateException cause = new IllegalStateException("TargetNotPainted");
        IllegalArgumentException failure = new IllegalArgumentException(String.join(" ", secrets), cause);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.ltm.rd.it." + secrets.get(0), secrets.get(1), secrets.get(2), 947261),
                new StackTraceElement(TwoJvmDiagnosticsTest.class.getName(),
                        "productLogAndCauseStructureSurviveWithoutSecretsOrForgedMetadata", secrets.get(1), 947261) });
        LogRecord record = new LogRecord(Level.WARNING, String.join(" ", secrets));
        record.setLoggerName("com.ltm.rd.common.SessionLifecycle");
        record.setThrown(failure);
        List<String> lines = new ArrayList<>();
        TwoJvmDiagnostics.productLog(lines::add, record);
        TwoJvmDiagnostics.failure(lines::add, "snapshot_monitor", new ExecutionException(failure));
        secrets.forEach(value -> lines.add(value)); // Parent must also reject unknown raw/control text.
        record.setLoggerName(String.join(" ", secrets));
        TwoJvmDiagnostics.productLog(lines::add, record);
        record.setLoggerName(null);
        TwoJvmDiagnostics.productLog(lines::add, record);
        String safe = String.join("\n", lines.stream().map(TwoJvmDiagnostics::sanitizeLine).toList());
        Files.writeString(directory.resolve("diagnostics.txt"), safe);
        assertTrue(safe.contains("logger=com.ltm.rd.common.SessionLifecycle level=WARNING code=Redacted"));
        assertTrue(safe.contains("cause=1 type=java.lang.IllegalStateException reason=TargetNotPainted"));
        assertTrue(safe.contains("TwoJvmDiagnosticsTest.productLogAndCauseStructureSurviveWithoutSecretsOrForgedMetadata("));
        assertTrue(safe.contains("logger=UnknownLogger"));
        try (var artifacts = Files.list(directory)) {
            for (Path artifact : artifacts.toList()) {
                String persisted = Files.readString(artifact);
                for (String secret : secrets) assertFalse(persisted.contains(secret), "persisted secret fixture");
            }
        }
    }
}
