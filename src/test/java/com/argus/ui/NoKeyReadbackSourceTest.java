package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 6.6: the structural invariant-7 guard on the key-vault panel. A stored key is never
 * rendered back in plaintext -- enforced here by scanning source, not by review discipline (the
 * {@code ToolkitFreeSourceTest}/{@code PackageBoundaryTest} technique).
 */
class NoKeyReadbackSourceTest {

    private static final Path UI_SOURCE_DIR = Path.of("src/main/java/com/argus/ui");

    private static final List<String> SCANNED_FILES = List.of(
            "KeyVaultController.java",
            "ApiKeyValidation.java",
            "ApiKeyRow.java",
            "ApiKeyRows.java",
            "ApiKeySource.java");

    @Test
    void noneOfTheseFilesReadsAValueOutOfTheVault() {
        for (String fileName : SCANNED_FILES) {
            assertFalse(sourceOf(fileName).contains("vault.get("),
                    fileName + " must never call vault.get(...)");
        }
    }

    @Test
    void controllerNeverRepopulatesTheKeyField() {
        assertFalse(sourceOf("KeyVaultController.java").contains("keyField.setText("),
                "KeyVaultController must never call keyField.setText(...) -- only clear()");
    }

    @Test
    void noneOfTheseFilesLogsAnything() {
        for (String fileName : SCANNED_FILES) {
            String source = sourceOf(fileName);
            assertFalse(source.contains("System.Logger"), fileName + " must not use System.Logger");
            assertFalse(source.contains("System.out"), fileName + " must not use System.out");
            assertFalse(source.contains("System.err"), fileName + " must not use System.err");
            assertFalse(source.contains("printStackTrace"),
                    fileName + " must not call printStackTrace");
            assertFalse(source.contains("getLogger"), fileName + " must not call getLogger");
        }
    }

    @Test
    void controllerNeverForwardsAnExceptionMessage() {
        assertFalse(sourceOf("KeyVaultController.java").contains("getMessage()"),
                "KeyVaultController must never call exception.getMessage() -- use fixed literals");
    }

    @Test
    void controllerDeclaresNoFieldOfTypeString() {
        for (Field field : KeyVaultController.class.getDeclaredFields()) {
            assertTrue(field.getType() != String.class,
                    "KeyVaultController must declare no String field, found: " + field.getName());
        }
    }

    // ---- P3-03 §6.10: the same invariant-7 discipline, extended to the new Notifications
    // settings screen. NotificationSettingsController.java is checked directly here, the same
    // way KeyVaultController's own dedicated tests above check it directly rather than through
    // SCANNED_FILES -- WebhookSettings.java is deliberately NOT added to SCANNED_FILES or these
    // checks: its fromVault(...) is the one place in ui that legitimately calls vault.get(...)
    // (plan §3.4/§0.4) and logs one non-secret line on a closed/corrupt vault, so the blanket
    // "never calls vault.get(" / "never logs" rules below would misfire on it. See the
    // implementer's final report for this flagged deviation from the task list's literal
    // wording.

    @Test
    void notificationSettingsControllerNeverCallsVaultGet() {
        assertFalse(sourceOf("NotificationSettingsController.java").contains("vault.get("),
                "NotificationSettingsController must never call vault.get(...)");
    }

    @Test
    void notificationSettingsControllerNeverRepopulatesTheUrlField() {
        assertFalse(sourceOf("NotificationSettingsController.java").contains("urlField.setText("),
                "NotificationSettingsController must never call urlField.setText(...) -- only clear()");
    }

    @Test
    void notificationSettingsControllerDeclaresNoFieldOfTypeString() {
        for (Field field : NotificationSettingsController.class.getDeclaredFields()) {
            assertTrue(field.getType() != String.class,
                    "NotificationSettingsController must declare no String field, found: "
                            + field.getName());
        }
    }

    @Test
    void notificationSettingsControllerNeverForwardsAnExceptionMessage() {
        assertFalse(sourceOf("NotificationSettingsController.java").contains("getMessage()"),
                "NotificationSettingsController must never call exception.getMessage() -- use "
                        + "fixed literals");
    }

    private static String sourceOf(String fileName) {
        Path file = UI_SOURCE_DIR.resolve(fileName);
        assertTrue(Files.isRegularFile(file), "missing expected source file: " + file);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
