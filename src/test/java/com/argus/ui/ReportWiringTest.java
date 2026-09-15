package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanHistory;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 7.5 (W1-W6): reflective + source-scan guards for the report export screen -- no
 * toolkit start, never calls {@code Application.launch}, {@code new App()} or
 * {@code Platform.startup} (the {@code TimelineWiringTest} shape).
 */
class ReportWiringTest {

    private static final List<String> NEW_MAIN_FILES = List.of(
            "src/main/java/com/argus/ui/ReportModel.java",
            "src/main/java/com/argus/ui/HtmlReport.java",
            "src/main/java/com/argus/ui/Reports.java",
            "src/main/java/com/argus/ui/ReportFiles.java",
            "src/main/java/com/argus/ui/ReportController.java");

    @Test
    void w1ReportControllerDeclaresSetHistorySetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory = ReportController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setOnClose = ReportController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = ReportController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void w2AppDeclaresShowReportMethodAndReportRootAndReportControllerFields()
            throws NoSuchMethodException {
        assertNotNull(findField(App.class, ReportController.class));
        assertNotNull(findFieldByName(App.class, "reportRoot"));

        Method showReport = App.class.getDeclaredMethod("showReport", javafx.scene.Scene.class);
        assertTrue(Modifier.isPrivate(showReport.getModifiers()),
                "showReport must be private -- nothing outside App routes navigation");

        assertDoesNotThrow(() -> App.class.getMethod("stop"));
    }

    @Test
    void w3NoFieldOnReportControllerIsVolatile() {
        for (Field field : ReportController.class.getDeclaredFields()) {
            assertFalse(Modifier.isVolatile(field.getModifiers()),
                    "no field on ReportController may be volatile (invariant 5, plan §4.3), "
                            + "found: " + field);
        }
    }

    @Test
    void w4ReportControllerHasNoVaultFieldAndNoVaultImport() throws IOException {
        for (Field field : ReportController.class.getDeclaredFields()) {
            assertFalse(field.getType() == com.argus.core.Vault.class,
                    "ReportController must declare no field of type Vault");
        }
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/ReportController.java"));
        assertFalse(source.contains("import com.argus.core.Vault;"),
                "ReportController must not import com.argus.core.Vault");
    }

    @Test
    void w5ReportControllerSourceContainsNoComArgusDbReference() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/ReportController.java"));
        assertFalse(source.contains("com.argus.db"),
                "ReportController.java must not reference com.argus.db anywhere");
    }

    @Test
    void w6NoNewFileContainsAClockRead() throws IOException {
        List<String> forbidden = List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "System.currentTimeMillis");
        for (String fileName : NEW_MAIN_FILES) {
            String source = readSource(Path.of(fileName));
            for (String pattern : forbidden) {
                assertFalse(source.contains(pattern),
                        fileName + " must not contain a clock read (" + pattern + "), plan §5.4");
            }
        }
    }

    private static Field findField(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }

    private static Field findFieldByName(Class<?> owner, String name) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
