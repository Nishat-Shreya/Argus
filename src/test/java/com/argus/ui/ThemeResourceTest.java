package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Toolkit-free checks on the shared stylesheet resource: it is packaged where JavaFX will
 * look for it, and it carries every colour token the spec requires, inside {@code .root}.
 * Uses only {@code java.lang.Class#getResource} and string assertions — no JavaFX types.
 */
class ThemeResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/theme.css";

    private static String css;

    @BeforeAll
    static void readThemeFromClasspath() throws Exception {
        try (InputStream in = ThemeResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "theme.css not packaged at " + CLASSPATH_LOCATION);
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void themeCssIsOnTheClasspath() {
        assertNotNull(ThemeResourceTest.class.getResource(CLASSPATH_LOCATION),
                "theme.css must be on the classpath at " + CLASSPATH_LOCATION);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "bg-base,        #0a0e1c",
            "bg-panel,       #111830",
            "bg-panel-alt,   #0d1326",
            "border,         #1f2a4a",
            "border-strong,  #34467a",
            "accent,         #3b82f6",
            "accent-hover,   #5b93f7",
            "primary,        #3b82f6",
            "secondary,      #8b5cf6",
            "teal,           #22d3ee",
            "danger-bg,      #1f1220",
            "danger-border,  #4a1f2a",
            "danger-text,    #f87171",
            "warning-bg,     #201a10",
            "warning-border, #4a3a14",
            "warning-text,   #fbbf24",
            "success-bg,     #0e2018",
            "success-border, #16412c",
            "success-text,   #34d399",
            "text-primary,   #e8ecf7",
            "text-secondary, #9aa5c9",
            "text-muted,     #5c6690",
    })
    void themeCssDeclaresEveryColorTokenFromTheSpec(String name, String hex) {
        Pattern p = Pattern.compile(
                "-argus-" + Pattern.quote(name) + "\\s*:\\s*" + Pattern.quote(hex) + "\\s*;",
                Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher(css).find(),
                "theme.css must declare -argus-" + name + ": " + hex + ";");
    }

    @Test
    void themeCssDeclaresTokensInsideRootBlock() {
        int rootIndex = indexOfRootSelector(css);
        assertTrue(rootIndex >= 0, "theme.css must contain a .root selector");

        Matcher firstToken = Pattern.compile("-argus-").matcher(css);
        assertTrue(firstToken.find(), "theme.css must declare at least one -argus- token");
        assertTrue(firstToken.start() > rootIndex,
                "the first -argus- token must be declared after the .root selector");
    }

    @Test
    void themeCssDeclaresTheDropZoneActiveSelector() {
        Pattern selector = Pattern.compile("\\.drop-zone-active\\b");
        assertTrue(selector.matcher(css).find(),
                "theme.css must declare a .drop-zone-active selector (plan §3.8) -- it is "
                        + "applied/removed in Java on DRAG_ENTERED/DRAG_EXITED, so it is invisible "
                        + "to DashboardViewResourceTest's FXML styleClass scan");
    }

    @Test
    void themeCssDeclaresTheNoteFieldExpandedSelector() {
        Pattern selector = Pattern.compile("\\.note-field-expanded\\b");
        assertTrue(selector.matcher(css).find(),
                "theme.css must declare a .note-field-expanded selector (P3-06 plan §3.4) -- it "
                        + "is applied/removed in Java on focus gained/lost, so it is invisible to "
                        + "FindingsDetailViewResourceTest's FXML styleClass scan (the "
                        + ".drop-zone-active precedent)");
    }

    private static int indexOfRootSelector(String text) {
        Matcher m = Pattern.compile("\\.root\\s*\\{").matcher(text);
        return m.find() ? m.start() : -1;
    }
}
