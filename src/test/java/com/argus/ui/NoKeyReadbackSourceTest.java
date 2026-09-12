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
