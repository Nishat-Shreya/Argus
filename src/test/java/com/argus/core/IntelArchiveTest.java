package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.Database;
import com.argus.db.NewScan;
import com.argus.db.ScanRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link IntelArchive}, the read/write gateway for a scan's persisted intel/KEV enrichment. */
class IntelArchiveTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void atCreatesNoFile() {
        Path dbFile = tempDir.resolve("argus.db");
        IntelArchive.at(dbFile);
        assertFalse(Files.exists(dbFile));
    }

    @Test
    void atDefaultLocationSharesTheDatabaseFileWithScanArchive() {
        assertEquals(ScanArchive.atDefaultLocation().databaseFile(),
                IntelArchive.atDefaultLocation().databaseFile());
    }

    @Test
    void saveSummarizesEveryOutcomeAndHasNoKevMatchWhenNoneWasFound() throws Exception {
        long scanId = seedScan();
        IntelArchive archive = IntelArchive.at(tempDir.resolve("argus.db"));

        IntelReport report = new IntelReport(IntelSubject.domain("example.com"), List.of(
                IntelSourceOutcome.ok("virustotal", okResult()),
                IntelSourceOutcome.unsupported("shodan")));

        PersistedIntelResult saved = archive.save(scanId, report, KevMatchResult.none());

        assertEquals(scanId, saved.scanId());
        assertFalse(saved.kevMatched());
        assertTrue(saved.summary().contains("virustotal: ok"));
        assertTrue(saved.summary().contains("shodan: unsupported_subject"));
    }

    @Test
    void saveMarksKevMatchedWhenTheScorerFoundAMatch() throws Exception {
        long scanId = seedScan();
        IntelArchive archive = IntelArchive.at(tempDir.resolve("argus.db"));

        IntelReport report = new IntelReport(IntelSubject.domain("example.com"),
                List.of(IntelSourceOutcome.ok("virustotal", okResult())));
        KevMatchResult kevMatch = new KevMatchResult(List.of(
                new KevEntry(new CveId("CVE-2024-1234"), "Vendor", "Product", "Name", false)));

        PersistedIntelResult saved = archive.save(scanId, report, kevMatch);

        assertTrue(saved.kevMatched());
        assertTrue(saved.summary().contains("KEV MATCH: 1"));
    }

    @Test
    void findOnAScanWithNoResultIsEmpty() throws Exception {
        long scanId = seedScan();
        IntelArchive archive = IntelArchive.at(tempDir.resolve("argus.db"));
        assertEquals(Optional.empty(), archive.find(scanId));
    }

    @Test
    void findReturnsWhatWasSaved() throws Exception {
        long scanId = seedScan();
        IntelArchive archive = IntelArchive.at(tempDir.resolve("argus.db"));
        IntelReport report = new IntelReport(IntelSubject.domain("example.com"),
                List.of(IntelSourceOutcome.ok("virustotal", okResult())));
        archive.save(scanId, report, KevMatchResult.none());

        Optional<PersistedIntelResult> found = archive.find(scanId);
        assertTrue(found.isPresent());
        assertEquals(scanId, found.get().scanId());
    }

    @Test
    void saveAgainstAnUnknownScanIdWrapsThePersistenceException() {
        IntelArchive archive = IntelArchive.at(tempDir.resolve("argus.db"));
        IntelReport report = new IntelReport(IntelSubject.domain("example.com"),
                List.of(IntelSourceOutcome.ok("virustotal", okResult())));

        ScanArchiveException thrown = assertThrows(ScanArchiveException.class,
                () -> archive.save(9999L, report, KevMatchResult.none()));
        assertTrue(thrown.getCause() instanceof com.argus.db.PersistenceException);
    }

    private static IntelResult okResult() {
        return new IntelResult("virustotal", IntelSubject.domain("example.com"),
                IntelVerdict.HARMLESS, 0, List.of(), java.util.Map.of());
    }

    private long seedScan() throws Exception {
        Database database = Database.open(tempDir.resolve("argus.db"));
        ScanRepository scanRepository = new ScanRepository(database);
        return scanRepository.insert(NewScan.running("example.com", FIXED_INSTANT)).id();
    }
}
