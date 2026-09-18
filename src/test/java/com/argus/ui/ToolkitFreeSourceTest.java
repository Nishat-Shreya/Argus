package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Section 6.11: scans the sources of the toolkit-free classes (§3.1-§3.7) for {@code javafx.}
 * imports. The {@code PackageBoundaryTest} technique, applied inside {@code ui}. Only
 * {@code DashboardController}, the modified {@code App} and {@code AnimationUtils} are allowed
 * to import JavaFX in this item.
 *
 * P3-06 adds six: {@code NoteRow}, {@code NoteRows}, {@code NoteEntry}, {@code Notes},
 * {@code NoteValidation} (the plan's own list, 59 &rarr; 64) plus {@code NoteDeletion} — the
 * operator-added delete-confirmation gate (65) — a genuinely toolkit-free pure decision, not a
 * convenience addition. {@code FindingsDetailController} is NOT in this list: like every other
 * screen controller, it is JavaFX-touching by design.
 *
 * P3-07 adds three: {@code Tags}, {@code TagFilter}, {@code TagValidation} (65 &rarr; 68, plan
 * §5 C7).
 *
 * P3-08 adds three: {@code ScheduledScanValidation}, {@code ScheduledScanRow}, {@code
 * ScheduledScanRows} (68 &rarr; 71). {@code ScheduledScansController} is NOT in this list: like
 * every other screen controller, it is JavaFX-touching by design.
 */
class ToolkitFreeSourceTest {

    private static final Path UI_SOURCE_DIR = Path.of("src/main/java/com/argus/ui");

    private static final List<String> TOOLKIT_FREE_CLASSES = List.of(
            "DashboardValidation.java",
            "ScanPlan.java",
            "FindingRow.java",
            "FindingRows.java",
            "PauseGate.java",
            "WorkerStatus.java",
            "ScanProgress.java",
            "ScanOutcome.java",
            "ScanEventListener.java",
            "ScanJob.java",
            "ScanJobFactory.java",
            "PortScanJob.java",
            "SubdomainScanJob.java",
            "DefaultScanJobFactory.java",
            "ScanCoordinator.java",
            "ScanSaver.java",
            "ApiKeySource.java",
            "ApiKeyRow.java",
            "ApiKeyRows.java",
            "ApiKeyValidation.java",
            "DiffRow.java",
            "DiffRows.java",
            "ScanChoice.java",
            "ScanChoices.java",
            "DiffValidation.java",
            "ChartData.java",
            "ChartSlice.java",
            "ChartBar.java",
            "GraphNodeKind.java",
            "GraphNode.java",
            "GraphModel.java",
            "GraphModels.java",
            "GraphPoint.java",
            "GraphLayout.java",
            "GraphLegendEntry.java",
            "GraphViewport.java",
            "TimelinePoint.java",
            "TimelineTrack.java",
            "Timelines.java",
            "SnapshotRows.java",
            "DesktopNotification.java",
            "DesktopNotifier.java",
            "DesktopNotifiers.java",
            "ScanNotifications.java",
            "SystemTrayNotifier.java",
            "AlertChannel.java",
            "AlertChannels.java",
            "DesktopAlertChannel.java",
            "WebhookAlertChannel.java",
            "WebhookSettings.java",
            "ReportModel.java",
            "HtmlReport.java",
            "Reports.java",
            "ReportFiles.java",
            "DroppedContent.java",
            "TargetDrop.java",
            "QueueAdmission.java",
            "DroppedTargets.java",
            "TargetQueue.java",
            "NoteRow.java",
            "NoteRows.java",
            "NoteEntry.java",
            "Notes.java",
            "NoteValidation.java",
            "NoteDeletion.java",
            "Tags.java",
            "TagFilter.java",
            "TagValidation.java",
            "ScheduledScanValidation.java",
            "ScheduledScanRow.java",
            "ScheduledScanRows.java");

    @Test
    void noneOfTheOrchestrationClassesImportJavaFx() {
        assertTrue(Files.isDirectory(UI_SOURCE_DIR),
                "expected " + UI_SOURCE_DIR + " to exist from working directory "
                        + Path.of("").toAbsolutePath());

        for (String fileName : TOOLKIT_FREE_CLASSES) {
            Path file = UI_SOURCE_DIR.resolve(fileName);
            assertTrue(Files.isRegularFile(file), "missing expected source file: " + file);

            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String line : lines) {
                assertFalse(violatesImportRule(line), fileName + " has a forbidden javafx. import -> "
                        + line.trim());
            }
        }
    }

    @Test
    void exactlySeventyOneClassesAreChecked() {
        assertTrue(TOOLKIT_FREE_CLASSES.size() == 71,
                "expected 71 toolkit-free classes, found " + TOOLKIT_FREE_CLASSES.size());
    }

    private static boolean violatesImportRule(String line) {
        Pattern pattern = Pattern.compile("^\\s*import\\s+(static\\s+)?javafx\\.");
        return pattern.matcher(line).find();
    }
}
