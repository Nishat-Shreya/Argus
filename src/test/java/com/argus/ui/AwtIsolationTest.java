package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Section 6.4 (X1): exactly one {@code ui} source imports {@code java.awt.} —
 * {@code SystemTrayNotifier.java} — and that file imports no {@code javafx.}. The
 * {@code PackageBoundaryTest} / {@code ToolkitFreeSourceTest} technique, applied to the AWT
 * boundary instead of the JavaFX one.
 */
class AwtIsolationTest {

    private static final Path UI_SOURCE_DIR = Path.of("src/main/java/com/argus/ui");
    private static final Pattern AWT_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?java\\.awt\\.");
    private static final Pattern JAVAFX_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?javafx\\.");

    @Test
    void x1ExactlyOneFileImportsJavaAwtAndItIsSystemTrayNotifier() throws IOException {
        assertTrue(Files.isDirectory(UI_SOURCE_DIR),
                "expected " + UI_SOURCE_DIR + " to exist from working directory "
                        + Path.of("").toAbsolutePath());

        List<Path> awtImporters = new ArrayList<>();
        try (Stream<Path> files = Files.walk(UI_SOURCE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (containsMatch(file, AWT_IMPORT)) {
                    awtImporters.add(file);
                }
            }
        }

        assertEquals(1, awtImporters.size(),
                "expected exactly one java.awt.-importing file, found " + awtImporters);
        assertEquals("SystemTrayNotifier.java", awtImporters.get(0).getFileName().toString());
    }

    @Test
    void systemTrayNotifierImportsNoJavaFx() throws IOException {
        Path file = UI_SOURCE_DIR.resolve("SystemTrayNotifier.java");
        assertTrue(Files.isRegularFile(file), "missing expected source file: " + file);
        assertFalse(containsMatch(file, JAVAFX_IMPORT),
                "SystemTrayNotifier.java must not import javafx.");
    }

    private static boolean containsMatch(Path file, Pattern pattern) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
