package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.Database;
import com.argus.db.FindingDao;
import com.argus.db.FindingRecord;
import com.argus.db.NewFinding;
import com.argus.db.NewScan;
import com.argus.db.ScanRecord;
import com.argus.db.ScanRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScanDiffEngine} against real, persisted-and-reloaded {@link FindingRecord}s (plan §6.5).
 * Mirrors {@code com.argus.db.FindingDiffQueryTest} — which P1-04 wrote as a schema-fitness check
 * for this exact item — but exercises the real engine instead of a hand-rolled in-test diff.
 *
 * Uses a {@code @TempDir} SQLite FILE, never {@code jdbc:sqlite::memory:} (P1-04's standing
 * precedent). This test lives in {@code com.argus.core}, not {@code com.argus.db}, so it cannot
 * use the package-private {@code TempDatabases} helper; it inlines the one-line
 * {@code Database.open(...)} instead rather than widening that helper's visibility.
 */
class ScanDiffEngineRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private ScanRepository scanRepository;
    private FindingDao findingDao;

    @BeforeEach
    void setUp() throws Exception {
        Database database = Database.open(tempDir.resolve("argus.db"));
        scanRepository = new ScanRepository(database);
        findingDao = new FindingDao(database);
    }

    // ---- 6.5.1 ----

    @Test
    void twoScansSavedWithIdenticalFindingsProduceAnEmptyDiff() throws Exception {
        List<NewFinding> shared = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.subdomain("a.example.com"));
        long baselineId = newScanWith(shared);
        long currentId = newScanWith(shared);

        ScanDiff diff = diffScans(baselineId, currentId);

        assertTrue(diff.isEmpty());
    }

    // ---- 6.5.2 ----

    @Test
    void additionSurvivesTheRoundTrip() throws Exception {
        long baselineId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));
        long currentId = newScanWith(List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN")));

        ScanDiff diff = diffScans(baselineId, currentId);

        assertEquals(1, diff.added().size());
        assertEquals(443, diff.added().get(0).port());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.5.3 ----

    @Test
    void removalSurvivesTheRoundTrip() throws Exception {
        long baselineId = newScanWith(List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN")));
        long currentId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));

        ScanDiff diff = diffScans(baselineId, currentId);

        assertEquals(1, diff.removed().size());
        assertEquals(443, diff.removed().get(0).port());
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.5.4 ----

    @Test
    void openToFilteredRoundTripsAsExactlyOneChangeWithBothStatesReadable() throws Exception {
        long baselineId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));
        long currentId = newScanWith(List.of(NewFinding.port("host", 80, "FILTERED")));

        ScanDiff diff = diffScans(baselineId, currentId);

        assertEquals(1, diff.changed().size());
        FindingChange change = diff.changed().get(0);
        assertEquals("OPEN", change.baseline().state());
        assertEquals("FILTERED", change.current().state());
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }

    // ---- 6.5.5 ----

    @Test
    void aMixedPortAndSubdomainScanPairDiffsCorrectlyAfterPersistence() throws Exception {
        long baselineId = newScanWith(List.of(
                NewFinding.port("host", 22, "OPEN"),
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.subdomain("a.example.com")));
        long currentId = newScanWith(List.of(
                NewFinding.port("host", 22, "OPEN"),
                NewFinding.port("host", 80, "FILTERED"),
                NewFinding.subdomain("b.example.com")));

        ScanDiff diff = diffScans(baselineId, currentId);

        assertEquals(1, diff.added().size());
        assertEquals("b.example.com", diff.added().get(0).subject());
        assertEquals(1, diff.removed().size());
        assertEquals("a.example.com", diff.removed().get(0).subject());
        assertEquals(1, diff.changed().size());
        assertEquals(80, diff.changed().get(0).key().port());
    }

    // ---- helpers ----

    private long newScanWith(List<NewFinding> findings) throws Exception {
        ScanRecord scan = scanRepository.insert(NewScan.running("example.com", NOW));
        findingDao.insertAll(scan.id(), findings);
        return scan.id();
    }

    private ScanDiff diffScans(long baselineScanId, long currentScanId) throws Exception {
        List<FindingRecord> baseline = findingDao.findByScan(baselineScanId);
        List<FindingRecord> current = findingDao.findByScan(currentScanId);
        return ScanDiffEngine.diff(baseline, current);
    }
}
