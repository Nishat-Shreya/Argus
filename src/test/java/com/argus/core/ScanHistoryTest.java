package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T's driver for plan §3.3, §7.5: {@link ScanHistory} — the read gateway, real {@code @TempDir}
 *  SQLite file, never {@code :memory:} (P1-04 standing rule). */
class ScanHistoryTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @TempDir
    Path tempDir;

    @Test
    void atDatabaseFileReturnsThePathAndConstructionDoesNoIo() {
        Path file = tempDir.resolve("argus.db");
        ScanHistory history = ScanHistory.at(file);

        assertEquals(file, history.databaseFile());
        assertFalse(Files.exists(file));
    }

    @Test
    void atDefaultLocationResolvesUnderAppDataDirectory() {
        Path expected = AppDataDirectory.resolve().resolve(ScanArchive.DEFAULT_DATABASE_FILE_NAME);
        assertEquals(expected, ScanHistory.atDefaultLocation().databaseFile());
    }

    @Test
    void listScansOnAFreshFileReturnsAnEmptyListAndDoesNotThrow() throws Exception {
        ScanHistory history = ScanHistory.at(tempDir.resolve("argus.db"));
        assertEquals(List.of(), history.listScans());
    }

    @Test
    void listScansReturnsAllSavedScansNewestFirstWithStatusesProjected() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long first = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));
        long second = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.CANCELLED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        List<ScanSummary> summaries = history.listScans();

        assertEquals(2, summaries.size());
        assertEquals(second, summaries.get(0).id());
        assertEquals("CANCELLED", summaries.get(0).status());
        assertEquals(first, summaries.get(1).id());
        assertEquals("COMPLETED", summaries.get(1).status());
    }

    @Test
    void compareOfTwoRealSavedSessionsReturnsExpectedAddedRemovedAndChangedSets() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        List<Object> baselineFindings = List.of(
                new PortResult("host", 80, PortState.OPEN),
                new PortResult("host", 22, PortState.OPEN),
                new Subdomain("a.example.com"));
        List<Object> currentFindings = List.of(
                new PortResult("host", 80, PortState.FILTERED),
                new Subdomain("a.example.com"),
                new Subdomain("b.example.com"));

        long baselineId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, baselineFindings));
        long currentId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED, currentFindings));

        ScanHistory history = ScanHistory.at(file);
        ScanComparison comparison = history.compare(baselineId, currentId);

        assertEquals(1, comparison.diff().added().size());
        assertEquals("b.example.com", comparison.diff().added().get(0).subject());
        assertEquals(1, comparison.diff().removed().size());
        assertEquals(22, comparison.diff().removed().get(0).port());
        assertEquals(1, comparison.diff().changed().size());
        assertEquals("OPEN", comparison.diff().changed().get(0).baseline().state());
        assertEquals("FILTERED", comparison.diff().changed().get(0).current().state());
    }

    @Test
    void compareOfIdenticalContentReturnsAnEmptyDiff() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        List<Object> findings = List.of(new PortResult("host", 80, PortState.OPEN));

        long baselineId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, findings));
        long currentId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED, findings));

        ScanHistory history = ScanHistory.at(file);
        ScanComparison comparison = history.compare(baselineId, currentId);

        assertTrue(comparison.diff().isEmpty());
    }

    @Test
    void aPortGoingOpenToFilteredLandsInChangedNotAddedPlusRemoved() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long baselineId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of(new PortResult("host", 80, PortState.OPEN))));
        long currentId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED,
                List.of(new PortResult("host", 80, PortState.FILTERED))));

        ScanHistory history = ScanHistory.at(file);
        ScanComparison comparison = history.compare(baselineId, currentId);

        assertTrue(comparison.diff().added().isEmpty());
        assertTrue(comparison.diff().removed().isEmpty());
        assertEquals(1, comparison.diff().changed().size());
    }

    @Test
    void anUnknownBaselineIdThrowsScanArchiveException() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long currentId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        assertThrows(ScanArchiveException.class, () -> history.compare(999L, currentId));
    }

    @Test
    void anUnknownCurrentIdThrowsScanArchiveException() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long baselineId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        assertThrows(ScanArchiveException.class, () -> history.compare(baselineId, 999L));
    }

    @Test
    void aScanWithZeroFindingsComparesCleanly() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long emptyId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));
        long populatedId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED,
                List.of(new Subdomain("a.example.com"))));

        ScanHistory history = ScanHistory.at(file);
        ScanComparison comparison = history.compare(emptyId, populatedId);

        assertEquals(1, comparison.diff().added().size());
        assertTrue(comparison.diff().removed().isEmpty());
    }

    @Test
    void compareReturnsBothScanSummariesNotSwapped() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long baselineId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));
        long currentId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        ScanComparison comparison = history.compare(baselineId, currentId);

        assertEquals(baselineId, comparison.baseline().id());
        assertEquals(currentId, comparison.current().id());
    }
}
