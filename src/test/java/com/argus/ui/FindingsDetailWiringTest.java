package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.AnnotationArchive;
import com.argus.core.ScanHistory;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Plan §6.6 W1-W7: reflective + source-scan guards for the findings-detail screen -- no toolkit
 * start, never calls {@code Application.launch}, {@code new App()} or {@code Platform.startup}
 * (the {@code TimelineWiringTest}/{@code ReportWiringTest} shape). Also covers the
 * operator-added delete-confirmation dialog (CHANGED from the plan's R9 default).
 */
class FindingsDetailWiringTest {

    private static final List<String> NEW_MAIN_FILES = List.of(
            "src/main/java/com/argus/db/NewAnnotation.java",
            "src/main/java/com/argus/db/AnnotationRecord.java",
            "src/main/java/com/argus/db/AnnotationDao.java",
            "src/main/java/com/argus/core/FindingNote.java",
            "src/main/java/com/argus/core/FindingNotes.java",
            "src/main/java/com/argus/core/AnnotationArchive.java",
            "src/main/java/com/argus/ui/NoteRow.java",
            "src/main/java/com/argus/ui/NoteRows.java",
            "src/main/java/com/argus/ui/NoteEntry.java",
            "src/main/java/com/argus/ui/Notes.java",
            "src/main/java/com/argus/ui/NoteValidation.java",
            "src/main/java/com/argus/ui/NoteDeletion.java",
            "src/main/java/com/argus/ui/FindingsDetailController.java");

    @Test
    void w1ControllerDeclaresSetHistorySetNotesSetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory =
                FindingsDetailController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setNotes =
                FindingsDetailController.class.getMethod("setNotes", AnnotationArchive.class);
        assertTrue(Modifier.isPublic(setNotes.getModifiers()));

        Method setOnClose = FindingsDetailController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = FindingsDetailController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void w2NoDeclaredFieldIsVolatile() {
        for (Field field : FindingsDetailController.class.getDeclaredFields()) {
            assertFalse(Modifier.isVolatile(field.getModifiers()),
                    "no field on FindingsDetailController may be volatile (invariant 5, plan "
                            + "§3.4), found: " + field);
        }
    }

    @Test
    void w3ControllerSourceContainsNoComArgusDbReference() throws IOException {
        String source = readSource(
                Path.of("src/main/java/com/argus/ui/FindingsDetailController.java"));
        assertFalse(source.contains("com.argus.db"),
                "FindingsDetailController.java must not reference com.argus.db anywhere");
    }

    @Test
    void w4ControllerHasNoVaultFieldAndNoVaultImport() throws IOException {
        for (Field field : FindingsDetailController.class.getDeclaredFields()) {
            assertFalse(field.getType() == com.argus.core.Vault.class,
                    "FindingsDetailController must declare no field of type Vault");
        }
        String source = readSource(
                Path.of("src/main/java/com/argus/ui/FindingsDetailController.java"));
        assertFalse(source.contains("import com.argus.core.Vault;"),
                "FindingsDetailController must not import com.argus.core.Vault");
    }

    @Test
    void w5TheClockFenceInstantNowAppearsExactlyOnceAndOnlyInFindingsDetailController()
            throws IOException {
        List<String> forbidden = List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "System.currentTimeMillis");
        String controllerFile = "src/main/java/com/argus/ui/FindingsDetailController.java";

        for (String fileName : NEW_MAIN_FILES) {
            String source = readSource(Path.of(fileName));
            for (String pattern : forbidden) {
                if (fileName.equals(controllerFile) && pattern.equals("Instant.now")) {
                    continue;
                }
                assertFalse(source.contains(pattern),
                        fileName + " must not contain a clock read (" + pattern + "), plan §0.2");
            }
        }

        String controllerSource = readSource(Path.of(controllerFile));
        Matcher matches = Pattern.compile(Pattern.quote("Instant.now")).matcher(controllerSource);
        int count = 0;
        while (matches.find()) {
            count++;
        }
        assertTrue(count == 1,
                "Instant.now must appear exactly once in FindingsDetailController.java, found "
                        + count);
    }

    @Test
    void w6AnimationUtilsDeclaresExpandFieldAndNoLongerListsItAsUnimplemented() throws IOException {
        Method expandField = assertDoesNotThrow(
                () -> AnimationUtils.class.getMethod("expandField", javafx.scene.layout.Region.class,
                        double.class));
        assertTrue(Modifier.isPublic(expandField.getModifiers()));
        assertTrue(Modifier.isStatic(expandField.getModifiers()));

        String source = readSource(Path.of("src/main/java/com/argus/ui/AnimationUtils.java"));
        assertFalse(source.contains("click-to-expand  annotation field"),
                "AnimationUtils' class Javadoc must no longer list click-to-expand as "
                        + "unimplemented");
    }

    @Test
    void w7ControllerNamesBothDaemonThreadsAndEveryThreadIsDaemon() throws IOException {
        String source = readSource(
                Path.of("src/main/java/com/argus/ui/FindingsDetailController.java"));
        assertTrue(source.contains("\"argus-scan-history\""),
                "FindingsDetailController.java must dispatch scan-history reads on "
                        + "\"argus-scan-history\"");
        assertTrue(source.contains("\"argus-annotations\""),
                "FindingsDetailController.java must dispatch annotation reads/writes on "
                        + "\"argus-annotations\"");

        Matcher newThread = Pattern.compile("new Thread\\(").matcher(source);
        Matcher setDaemon = Pattern.compile("\\.setDaemon\\(true\\)").matcher(source);
        int newThreadCount = 0;
        while (newThread.find()) {
            newThreadCount++;
        }
        int setDaemonCount = 0;
        while (setDaemon.find()) {
            setDaemonCount++;
        }
        assertTrue(newThreadCount > 0, "expected at least one new Thread( in the controller");
        assertEquals(newThreadCount, setDaemonCount);
    }

    @Test
    void dashboardControllerDeclaresSetOpenFindingsDetailHandlerAndKeepsFreeze()
            throws NoSuchMethodException {
        Method setHandler = DashboardController.class.getMethod(
                "setOpenFindingsDetailHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpen = findMethod(DashboardController.class, "onOpenFindingsDetail");
        assertNotNull(onOpen, "DashboardController must declare @FXML onOpenFindingsDetail");
        assertNotNull(onOpen.getAnnotation(javafx.fxml.FXML.class));

        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenNotificationSettingsHandler",
                        Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenReportHandler", Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenTimelineHandler", Runnable.class));
        assertDoesNotThrow(() -> DashboardController.class.getMethod("shutdown"));
    }

    @Test
    void appDeclaresAPrivateShowFindingsDetailMethodAndAControllerFieldAndStillDeclaresStop()
            throws NoSuchMethodException {
        assertNotNull(findField(App.class, FindingsDetailController.class));

        Method showFindingsDetail =
                App.class.getDeclaredMethod("showFindingsDetail", javafx.scene.Scene.class);
        assertTrue(Modifier.isPrivate(showFindingsDetail.getModifiers()),
                "showFindingsDetail must be private -- nothing outside App routes navigation");

        assertDoesNotThrow(() -> App.class.getMethod("stop"));
    }

    // --- operator-added: the delete confirmation dialog (CHANGED from the plan's R9 default) ---

    @Test
    void deleteHandlerShowsAConfirmationAlertStyledWithTheAppTheme() throws IOException {
        String source = readSource(
                Path.of("src/main/java/com/argus/ui/FindingsDetailController.java"));
        assertTrue(source.contains("Alert.AlertType.CONFIRMATION"),
                "onDeleteNote must show a javafx.scene.control.Alert of type CONFIRMATION "
                        + "(operator-added requirement, CHANGED from the plan's R9 default of "
                        + "one-click delete)");
        assertTrue(source.contains("Theme.applyTo("),
                "the confirmation Alert's dialog pane scene must be styled via Theme.applyTo(...) "
                        + "(the P2-09 R4 popup-stylesheet-risk precedent)");
        assertTrue(source.contains("NoteDeletion.shouldDelete("),
                "onDeleteNote must gate the delete through the toolkit-free NoteDeletion.shouldDelete "
                        + "decision, not inline the Alert result check");
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static Field findField(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
