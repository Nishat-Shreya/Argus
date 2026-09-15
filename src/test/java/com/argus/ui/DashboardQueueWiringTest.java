package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P3-05 §6: W1-W7, reflective + source-scan guards for the drag-and-drop target queue -- no
 * toolkit start, never calls {@code Application.launch}, {@code new App()} or
 * {@code Platform.startup} (the {@code ReportWiringTest} shape). W4 is the composition guard:
 * {@code ScanCoordinator} was NOT modified by this item -- a test, not a promise (plan §0.1).
 */
class DashboardQueueWiringTest {

    private static final List<String> NEW_MAIN_FILES = List.of(
            "src/main/java/com/argus/ui/DroppedContent.java",
            "src/main/java/com/argus/ui/TargetDrop.java",
            "src/main/java/com/argus/ui/QueueAdmission.java",
            "src/main/java/com/argus/ui/DroppedTargets.java",
            "src/main/java/com/argus/ui/TargetQueue.java");

    private static final Set<String> SCAN_COORDINATOR_DECLARED_METHODS = Set.of(
            "start", "pause", "resume", "cancel", "isRunning", "isPaused", "close",
            "isPoolTerminated", "areThreadsFinished", "currentPipelineSizeForTest",
            "consumerLoop", "supervisorLoop", "reportRunningIfStarted",
            "reportEndedDueToCancellation", "snapshotOf", "currentPublished",
            "updateWorkerStatus", "progressSnapshot", "progressSnapshotSafe", "shutdownPool");

    @Test
    void w1DashboardControllerDeclaresTheQueueFxmlFieldsAndHandlers() {
        assertNotNull(findField(DashboardController.class, "queueDropZone"));
        assertNotNull(findField(DashboardController.class, "queueList"));
        assertNotNull(findField(DashboardController.class, "runQueueButton"));
        assertNotNull(findField(DashboardController.class, "removeQueuedButton"));
        assertNotNull(findField(DashboardController.class, "queueStatusLabel"));

        Method onRunQueue = findMethod(DashboardController.class, "onRunQueue");
        assertNotNull(onRunQueue, "DashboardController must declare @FXML onRunQueue");
        assertNotNull(onRunQueue.getAnnotation(javafx.fxml.FXML.class));

        Method onRemoveQueued = findMethod(DashboardController.class, "onRemoveQueued");
        assertNotNull(onRemoveQueued, "DashboardController must declare @FXML onRemoveQueued");
        assertNotNull(onRemoveQueued.getAnnotation(javafx.fxml.FXML.class));
    }

    @Test
    void w2NoFieldOnDashboardControllerIsVolatile() {
        for (Field field : DashboardController.class.getDeclaredFields()) {
            assertFalse(Modifier.isVolatile(field.getModifiers()),
                    "no field on DashboardController may be volatile (invariant 5, plan §4.3), "
                            + "found: " + field);
        }
    }

    @Test
    void w3DashboardControllerDeclaresAPrivateStartScanMethod() {
        Method startScan = findMethod(DashboardController.class, "startScan");
        assertNotNull(startScan, "DashboardController must declare startScan(String)");
        assertTrue(Modifier.isPrivate(startScan.getModifiers()));
        assertEquals(1, startScan.getParameterCount());
        assertEquals(String.class, startScan.getParameterTypes()[0]);
    }

    @Test
    void w4ScanCoordinatorWasNotModifiedByThisItem() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/ScanCoordinator.java"));

        assertFalse(source.contains("TargetQueue"),
                "ScanCoordinator.java must not reference TargetQueue");
        assertFalse(source.contains("QueueAdmission"),
                "ScanCoordinator.java must not reference QueueAdmission");
        assertFalse(source.contains("DroppedContent"),
                "ScanCoordinator.java must not reference DroppedContent");

        Set<String> declaredMethodNames = new LinkedHashSet<>();
        for (Method method : ScanCoordinator.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                declaredMethodNames.add(method.getName());
            }
        }
        assertEquals(SCAN_COORDINATOR_DECLARED_METHODS, declaredMethodNames,
                "ScanCoordinator's declared method-name set must be exactly the pre-P3-05 set "
                        + "-- a change here means the coordinator was modified");
    }

    @Test
    void w5NoneOfTheFiveNewFilesContainsAClockRead() throws IOException {
        List<String> forbidden = List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "System.currentTimeMillis");
        for (String fileName : NEW_MAIN_FILES) {
            String source = readSource(Path.of(fileName));
            for (String pattern : forbidden) {
                assertFalse(source.contains(pattern),
                        fileName + " must not contain a clock read (" + pattern + "), plan §6 W5");
            }
        }
    }

    @Test
    void w6NoneOfTheFiveNewFilesReferencesComArgusDb() throws IOException {
        for (String fileName : NEW_MAIN_FILES) {
            String source = readSource(Path.of(fileName));
            assertFalse(source.contains("com.argus.db"),
                    fileName + " must not reference com.argus.db anywhere");
        }
    }

    @Test
    void w7DashboardControllerUsesCopyOnlyTransferMode() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));

        assertTrue(source.contains("TransferMode.COPY"),
                "DashboardController.java must accept TransferMode.COPY (plan §0.2)");
        assertFalse(source.contains("TransferMode.ANY"),
                "DashboardController.java must not accept TransferMode.ANY -- COPY only");
        assertFalse(source.contains("TransferMode.MOVE"),
                "DashboardController.java must not accept TransferMode.MOVE -- COPY only");
    }

    private static Field findField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
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
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
