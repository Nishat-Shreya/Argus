package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Findings of a scan (plan §6.5): insertAll, empty list, query by scan/type, NULL round-trip,
 *  FK rejection, duplicate rejection, rollback-leaves-nothing. */
class FindingDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private Database database;
    private FindingDao dao;
    private ScanRepository scanRepository;
    private long scanId;

    @BeforeEach
    void setUp() throws Exception {
        database = TempDatabases.open(tempDir);
        dao = new FindingDao(database);
        scanRepository = new ScanRepository(database);
        scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
    }

    @Test
    void insertAllReturnsTheNumberInserted() throws Exception {
        int count = dao.insertAll(scanId, List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN"),
                NewFinding.subdomain("a.example.com")));
        assertEquals(3, count);
    }

    @Test
    void insertAllOfAnEmptyListIsANoOp() throws Exception {
        assertEquals(0, dao.insertAll(scanId, List.of()));
        assertEquals(List.of(), dao.findByScan(scanId));
    }

    @Test
    void findByScanReturnsEverythingInInsertionOrder() throws Exception {
        NewFinding f1 = NewFinding.port("host", 80, "OPEN");
        NewFinding f2 = NewFinding.subdomain("a.example.com");
        NewFinding f3 = NewFinding.port("host", 443, "OPEN");
        dao.insertAll(scanId, List.of(f1, f2, f3));

        List<FindingRecord> found = dao.findByScan(scanId);
        assertEquals(3, found.size());
        assertEquals(80, found.get(0).port());
        assertEquals("a.example.com", found.get(1).subject());
        assertEquals(443, found.get(2).port());
    }

    @Test
    void findByScanOfAnUnknownScanReturnsEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByScan(9999L));
    }

    @Test
    void portFindingRoundTripsAllFourColumns() throws Exception {
        dao.insertAll(scanId, List.of(NewFinding.port("10.0.0.1", 443, "OPEN")));
        FindingRecord record = dao.findByScan(scanId).get(0);
        assertEquals(FindingType.PORT, record.type());
        assertEquals("10.0.0.1", record.subject());
        assertEquals(443, record.port());
        assertEquals("OPEN", record.state());
    }

    @Test
    void subdomainFindingRoundTripsWithNullPortAndState() throws Exception {
        dao.insertAll(scanId, List.of(NewFinding.subdomain("a.example.com")));
        FindingRecord record = dao.findByScan(scanId).get(0);
        assertNull(record.port());
        assertNull(record.state());
    }

    @Test
    void wildcardSubdomainSubjectSurvivesTheRoundTrip() throws Exception {
        dao.insertAll(scanId, List.of(NewFinding.subdomain("*.example.com")));
        FindingRecord record = dao.findByScan(scanId).get(0);
        assertEquals("*.example.com", record.subject());
    }

    @Test
    void findByScanAndTypeFiltersCorrectly() throws Exception {
        dao.insertAll(scanId, List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.subdomain("a.example.com"),
                NewFinding.subdomain("b.example.com")));

        List<FindingRecord> ports = dao.findByScanAndType(scanId, FindingType.PORT);
        assertEquals(1, ports.size());
        assertEquals(FindingType.PORT, ports.get(0).type());

        List<FindingRecord> subdomains = dao.findByScanAndType(scanId, FindingType.SUBDOMAIN);
        assertEquals(2, subdomains.size());
        assertTrue(subdomains.stream().allMatch(f -> f.type() == FindingType.SUBDOMAIN));
    }

    @Test
    void findingsOfOneScanAreNotVisibleFromAnother() throws Exception {
        long otherScanId = scanRepository.insert(NewScan.running("example.org", NOW)).id();
        dao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN")));
        dao.insertAll(otherScanId, List.of(NewFinding.port("host", 443, "OPEN")));

        assertEquals(1, dao.findByScan(scanId).size());
        assertEquals(80, dao.findByScan(scanId).get(0).port());
        assertEquals(1, dao.findByScan(otherScanId).size());
        assertEquals(443, dao.findByScan(otherScanId).get(0).port());
    }

    @Test
    void insertAllRejectsAnUnknownScanId() {
        assertThrows(PersistenceException.class,
                () -> dao.insertAll(9999L, List.of(NewFinding.port("host", 80, "OPEN"))));
    }

    @Test
    void insertAllRejectsADuplicateIdentityWithinTheBatch() {
        assertThrows(PersistenceException.class, () -> dao.insertAll(scanId, List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 80, "CLOSED"))));
    }

    @Test
    void insertAllRejectsAFindingAlreadyStoredForThatScan() throws Exception {
        dao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN")));
        assertThrows(PersistenceException.class,
                () -> dao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN"))));
    }

    @Test
    void aFailedBatchInsertsNothing() throws Exception {
        List<NewFinding> batch = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 81, "OPEN"),
                NewFinding.subdomain("a.example.com"),
                NewFinding.port("host", 80, "CLOSED"), // duplicate identity with the first
                NewFinding.subdomain("b.example.com"));

        assertThrows(PersistenceException.class, () -> dao.insertAll(scanId, batch));
        assertEquals(List.of(), dao.findByScan(scanId));
    }

    @Test
    void theSameFindingMayExistInTwoDifferentScans() throws Exception {
        long otherScanId = scanRepository.insert(NewScan.running("example.org", NOW)).id();
        dao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN")));
        dao.insertAll(otherScanId, List.of(NewFinding.port("host", 80, "OPEN")));

        assertEquals(1, dao.findByScan(scanId).size());
        assertEquals(1, dao.findByScan(otherScanId).size());
    }

    @Test
    void insertAllRejectsNullListOrNullElement() {
        assertThrows(NullPointerException.class, () -> dao.insertAll(scanId, null));
        List<NewFinding> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> dao.insertAll(scanId, withNull));
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new FindingDao(null));
    }
}
