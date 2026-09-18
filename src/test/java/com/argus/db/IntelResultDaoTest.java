package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link IntelResultDao} against a real {@code @TempDir} file, never {@code :memory:}. */
class IntelResultDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private Database database;
    private IntelResultDao dao;
    private ScanRepository scanRepository;
    private long scanId;

    @BeforeEach
    void setUp() throws Exception {
        database = TempDatabases.open(tempDir);
        dao = new IntelResultDao(database);
        scanRepository = new ScanRepository(database);
        scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
    }

    @Test
    void saveReturnsARecordWithAGeneratedId() throws Exception {
        IntelResultRecord record =
                dao.save(scanId, new NewIntelResult("virustotal: ok", false, NOW));

        assertTrue(record.id() > 0);
        assertEquals(scanId, record.scanId());
        assertEquals("virustotal: ok", record.summary());
        assertFalse(record.kevMatched());
        assertEquals(NOW, record.createdAt());
    }

    @Test
    void findByScanOnAScanWithNoResultIsEmpty() throws Exception {
        assertEquals(Optional.empty(), dao.findByScan(scanId));
    }

    @Test
    void findByScanReturnsTheSavedResult() throws Exception {
        dao.save(scanId, new NewIntelResult("censys: ok; KEV MATCH: 1", true, NOW));

        Optional<IntelResultRecord> found = dao.findByScan(scanId);
        assertTrue(found.isPresent());
        assertTrue(found.get().kevMatched());
    }

    @Test
    void savingTwiceForTheSameScanReplacesTheFirstRow() throws Exception {
        dao.save(scanId, new NewIntelResult("first", false, NOW));
        dao.save(scanId, new NewIntelResult("second", true, NOW.plusSeconds(60)));

        Optional<IntelResultRecord> found = dao.findByScan(scanId);
        assertTrue(found.isPresent());
        assertEquals("second", found.get().summary());
        assertTrue(found.get().kevMatched());
    }

    @Test
    void saveAgainstAnUnknownScanIdFailsOnTheForeignKey() {
        assertThrows(PersistenceException.class,
                () -> dao.save(9999L, new NewIntelResult("virustotal: ok", false, NOW)));
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new IntelResultDao(null));
    }

    @Test
    void saveRejectsNullResult() {
        assertThrows(NullPointerException.class, () -> dao.save(scanId, null));
    }
}
