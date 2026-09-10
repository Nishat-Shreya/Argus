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
            "bg-base,        #0d0f0d",
            "bg-panel,       #111511",
            "bg-panel-alt,   #0a0d0a",
            "border,         #1f2b1f",
            "border-strong,  #2a5a35",
            "accent,         #3ddc5f",
            "accent-hover,   #4de870",
            "danger-bg,      #1a0e0e",
            "danger-border,  #3a1414",
            "danger-text,    #e8726e",
            "warning-bg,     #1a1608",
            "warning-border, #3a2f0e",
            "warning-text,   #d4a53d",
            "success-bg,     #0e1a10",
            "success-border, #14361c",
            "text-primary,   #c9d6c9",
            "text-secondary, #8fa88f",
            "text-muted,     #5a6b5a",
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

    private static int indexOfRootSelector(String text) {
        Matcher m = Pattern.compile("\\.root\\s*\\{").matcher(text);
        return m.find() ? m.start() : -1;
    }
}
