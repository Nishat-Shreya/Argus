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
            "SnapshotRows.java");

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
    void exactlyFortyClassesAreChecked() {
        assertTrue(TOOLKIT_FREE_CLASSES.size() == 40,
                "expected 40 toolkit-free classes, found " + TOOLKIT_FREE_CLASSES.size());
    }

    private static boolean violatesImportRule(String line) {
        Pattern pattern = Pattern.compile("^\\s*import\\s+(static\\s+)?javafx\\.");
        return pattern.matcher(line).find();
    }
}
