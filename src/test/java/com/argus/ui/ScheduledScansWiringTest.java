package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-scan guards on the P3-08 scheduler additions to {@code DashboardController} -- the
 * {@code NotificationWiringTest} / {@code TagWiringTest} precedent for background-thread and
 * shutdown-ordering concerns that a plain unit test cannot reach (this controller cannot be
 * constructed off the FXML loader without the JavaFX toolkit).
 */
class ScheduledScansWiringTest {

    @Test
    void schedulerThreadIsNamedAndDaemon() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        assertTrue(source.contains("\"argus-scheduler\""),
                "the scheduler's thread factory must name its thread argus-scheduler");
        assertTrue(source.contains("thread.setDaemon(true)"),
                "the scheduler's thread factory must mark its thread daemon");
    }

    @Test
    void schedulerPoolHasTheCanonicalShutdownSequence() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        assertTrue(source.contains("schedulerPool.shutdown()"));
        assertTrue(source.contains("awaitTermination"));
        assertTrue(source.contains("schedulerPool.shutdownNow()"));
    }

    @Test
    void shutdownStopsTheSchedulerPoolBeforeClosingTheCoordinator() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        int shutdownMethodIndex = source.indexOf("public void shutdown()");
        int schedulerStopIndex = source.indexOf("shutdownSchedulerPool()", shutdownMethodIndex);
        int coordinatorCloseIndex = source.indexOf("coordinator.close()", shutdownMethodIndex);

        assertTrue(schedulerStopIndex >= 0,
                "shutdown() must call shutdownSchedulerPool()");
        assertTrue(coordinatorCloseIndex >= 0,
                "shutdown() must call coordinator.close()");
        assertTrue(schedulerStopIndex < coordinatorCloseIndex,
                "the scheduler pool must be stopped before the coordinator is closed, so no "
                        + "new scheduled scan can be admitted while shutdown is in progress");
    }

    @Test
    void checkScheduledScansGuardsAgainstANullArchive() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        int methodIndex = source.indexOf("private void checkScheduledScans()");
        int guardIndex = source.indexOf("scheduledScans == null", methodIndex);

        assertTrue(methodIndex >= 0, "DashboardController must declare checkScheduledScans()");
        assertTrue(guardIndex >= 0 && guardIndex < methodIndex + 400,
                "checkScheduledScans() must guard against scheduledScans being null");
    }

    @Test
    void triggerScheduledScanChecksQueueStoppedBeforeStartingOrQueuing() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        int methodIndex = source.indexOf("private void triggerScheduledScan(");
        int guardIndex = source.indexOf("queueStopped", methodIndex);
        int isRunningIndex = source.indexOf("coordinator.isRunning()", methodIndex);

        assertTrue(guardIndex >= 0 && guardIndex < isRunningIndex,
                "triggerScheduledScan must check queueStopped before touching the coordinator, "
                        + "so a tick landing during App.stop() can never start or queue a scan");
    }

    @Test
    void appWiresScheduledScanArchiveIntoTheDashboard() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        assertTrue(source.contains("dashboardController.setScheduledScans("),
                "App must inject a ScheduledScanArchive into the dashboard");
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
