package com.argus.core;

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
 * Turns architecture invariants 1 and 2 into a build failure rather than a review comment
 * by scanning the source tree for forbidden imports. Uses only {@code java.nio.file} and
 * {@code java.util.regex}; tolerates empty or absent package directories (P0-01 has no
 * production sources).
 */
class PackageBoundaryTest {

    private static final Path CORE = Path.of("src/main/java/com/argus/core");
    private static final Path DB = Path.of("src/main/java/com/argus/db");

    @Test
    void coreHasNoJavaFxImports() {
        assertNoImport(CORE, "javafx.");
    }

    @Test
    void coreDoesNotImportUi() {
        assertNoImport(CORE, "com.argus.ui.");
    }

    @Test
    void dbDoesNotImportCoreOrUi() {
        assertNoImport(DB, "com.argus.core.");
        assertNoImport(DB, "com.argus.ui.");
    }

    @Test
    void dbHasNoJavaFxImports() {
        assertNoImport(DB, "javafx.");
    }

    @Test
    void sourceTreeIsWhereWeThinkItIs() {
        assertTrue(Files.isDirectory(Path.of("src/main/java")),
                "src/main/java not found from working directory "
                        + Path.of("").toAbsolutePath());
    }

    @Test
    void importRuleFlagsPlainImport() {
        assertTrue(violatesImportRule("import javafx.scene.Node;", "javafx."));
        assertTrue(violatesImportRule("\timport javafx.scene.Node;", "javafx."));
        assertTrue(violatesImportRule("import com.argus.ui.MainController;", "com.argus.ui."));
    }

    @Test
    void importRuleFlagsStaticImport() {
        assertTrue(violatesImportRule(
                "import static javafx.application.Platform.runLater;", "javafx."));
        assertTrue(violatesImportRule(
                "import static com.argus.core.KevScorer.score;", "com.argus.core."));
    }

    @Test
    void importRuleIgnoresCommentsAndStrings() {
        assertFalse(violatesImportRule(" * {@code import javafx.scene.Node}", "javafx."));
        assertFalse(violatesImportRule("// import javafx.scene.Node;", "javafx."));
        assertFalse(violatesImportRule(
                "String s = \"import javafx.scene.Node;\";", "javafx."));
        assertFalse(violatesImportRule("import javafxx.scene.Node;", "javafx."));
    }

    /**
     * True when {@code line} is a Java {@code import} declaration — plain or
     * {@code import static} — for a type in the forbidden package. Start-anchored so that
     * comments, Javadoc ({@code {@code import javafx.Foo}}) and string literals mentioning
     * the word "import" do not trip it. {@code forbiddenPackage} is treated as a literal
     * prefix (e.g. {@code "javafx."}, {@code "com.argus.ui."}).
     */
    static boolean violatesImportRule(String line, String forbiddenPackage) {
        Pattern pattern = Pattern.compile(
                "^\\s*import\\s+(static\\s+)?" + Pattern.quote(forbiddenPackage));
        return pattern.matcher(line).find();
    }

    private static void assertNoImport(Path packageDir, String forbiddenPackage) {
        for (Path javaFile : javaFilesUnder(packageDir)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(javaFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String line : lines) {
                assertFalse(violatesImportRule(line, forbiddenPackage),
                        javaFile + " has a forbidden import of " + forbiddenPackage
                                + " -> " + line.trim());
            }
        }
    }

    private static List<Path> javaFilesUnder(Path packageDir) {
        if (!Files.isDirectory(packageDir)) {
            return List.of();
        }
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
