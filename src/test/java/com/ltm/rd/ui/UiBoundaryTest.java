package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The package-private test accessors of {@link AppRuntime} (and its {@code Seams}) must stay out of
 * the other ui classes, so the Swing classes of later tasks only see {@link Controller}.
 */
class UiBoundaryTest {
    private static final Path UI_SOURCES = Path.of("src", "main", "java", "com", "ltm", "rd", "ui");
    private static final Pattern FORBIDDEN =
            Pattern.compile("\\b(serverCore|inputExecutor|clientSession|tickerRegistrations)\\s*\\(|\\bSeams\\b");

    @Test
    void otherUiClassesDoNotUseTheRuntimeTestAccessors() throws IOException {
        assertTrue(Files.isDirectory(UI_SOURCES), "run from the project directory: " + UI_SOURCES.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(UI_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("AppRuntime.java")) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                var m = FORBIDDEN.matcher(text);
                if (m.find()) violations.add(file.getFileName() + ": " + m.group());
            }
        }
        assertEquals(List.of(), violations);
    }
}
