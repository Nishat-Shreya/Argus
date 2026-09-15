package com.argus.ui;

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
 * Section 6.13 of the P3-03 plan: no {@code ui} source imports {@code java.net.http} — outbound
 * HTTP lives in {@code core} (§0.3). The {@code AwtIsolationTest} / {@code PackageBoundaryTest}
 * technique.
 */
class UiHttpIsolationTest {

    private static final Path UI_SOURCE_DIR = Path.of("src/main/java/com/argus/ui");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(static\\s+)?java\\.net\\.http\\.");

    @Test
    void x1NoUiSourceImportsJavaNetHttp() {
        assertTrue(Files.isDirectory(UI_SOURCE_DIR),
                "expected " + UI_SOURCE_DIR + " to exist from working directory "
                        + Path.of("").toAbsolutePath());

        for (Path javaFile : javaFilesUnder(UI_SOURCE_DIR)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(javaFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String line : lines) {
                assertFalse(IMPORT_PATTERN.matcher(line).find(),
                        javaFile + " has a forbidden java.net.http import -> " + line.trim());
            }
        }
    }

    private static List<Path> javaFilesUnder(Path packageDir) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(result::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
