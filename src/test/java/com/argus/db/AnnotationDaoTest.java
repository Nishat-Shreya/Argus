package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan §6.2: {@link AnnotationDao} against a real {@code @TempDir} file, never {@code :memory:}. */
class AnnotationDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private Database database;
    private AnnotationDao dao;
    private ScanRepository scanRepository;
    private FindingDao findingDao;
    private long scanId;
    private long findingId;

    @BeforeEach
    void setUp() throws Exception {
        database = TempDatabases.open(tempDir);
        dao = new AnnotationDao(database);
        scanRepository = new ScanRepository(database);
        findingDao = new FindingDao(database);
        scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
        findingDao.insertAll(scanId, List.of(NewFinding.port("example.com", 80, "OPEN")));
        findingId = findingDao.findByScan(scanId).get(0).id();
    }

    // --- happy path -----------------------------------------------------------------------

    @Test
    void insertReturnsARecordWithAGeneratedId() throws Exception {
        AnnotationRecord record = dao.insert(findingId, new NewAnnotation("false positive", NOW));
        assertTrue(record.id() > 0);
        assertEquals(findingId, record.findingId());
        assertEquals("false positive", record.body());
        assertEquals(NOW, record.createdAt());
    }

    @Test
    void findByFindingReturnsTheInsertedAnnotation() throws Exception {
        dao.insert(findingId, new NewAnnotation("already patched", NOW));
        List<AnnotationRecord> found = dao.findByFinding(findingId);
        assertEquals(1, found.size());
        assertEquals("already patched", found.get(0).body());
    }

    @Test
    void threeNotesComeBackOldestFirstTieBrokenById() throws Exception {
        dao.insert(findingId, new NewAnnotation("second", NOW.plusSeconds(1)));
        dao.insert(findingId, new NewAnnotation("first", NOW));
        AnnotationRecord tieA = dao.insert(findingId, new NewAnnotation("tie-a", NOW.plusSeconds(2)));
        AnnotationRecord tieB = dao.insert(findingId, new NewAnnotation("tie-b", NOW.plusSeconds(2)));

        List<AnnotationRecord> found = dao.findByFinding(findingId);
        assertEquals(4, found.size());
        assertEquals("first", found.get(0).body());
        assertEquals("second", found.get(1).body());
        assertEquals(tieA.id(), found.get(2).id());
        assertEquals(tieB.id(), found.get(3).id());
    }

    @Test
    void findByScanOrdersByFindingThenCreatedAtThenIdAndExcludesOtherScans() throws Exception {
        findingDao.insertAll(scanId, List.of(NewFinding.subdomain("a.example.com")));
        long secondFindingId = findingDao.findByScanAndType(scanId, FindingType.SUBDOMAIN).get(0).id();

        long otherScanId = scanRepository.insert(NewScan.running("example.org", NOW)).id();
        findingDao.insertAll(otherScanId, List.of(NewFinding.subdomain("other.example.org")));
        long otherFindingId =
                findingDao.findByScanAndType(otherScanId, FindingType.SUBDOMAIN).get(0).id();

        dao.insert(secondFindingId, new NewAnnotation("on second finding", NOW));
        dao.insert(findingId, new NewAnnotation("on first finding", NOW));
        dao.insert(otherFindingId, new NewAnnotation("on other scan", NOW));

        List<AnnotationRecord> found = dao.findByScan(scanId);
        assertEquals(2, found.size());
        assertEquals(findingId, found.get(0).findingId());
        assertEquals(secondFindingId, found.get(1).findingId());
    }

    @Test
    void deleteByIdReturnsTrueThenFalse() throws Exception {
        AnnotationRecord record = dao.insert(findingId, new NewAnnotation("delete me", NOW));
        assertTrue(dao.deleteById(record.id()));
        assertFalse(dao.deleteById(record.id()));
    }

    @Test
    void aBodyWithNewlinesAndNonAsciiAndMaxLengthRoundTripsByteIdentical() throws Exception {
        String prefix = "line one\nligne deux — café\n";
        String body = prefix + "x".repeat(2000 - prefix.length());
        assertEquals(2000, body.length());
        AnnotationRecord record = dao.insert(findingId, new NewAnnotation(body, NOW));
        AnnotationRecord found = dao.findByFinding(findingId).get(0);
        assertEquals(body, found.body());
        assertEquals(record.body(), found.body());
    }

    // --- empty / absent ---------------------------------------------------------------------

    @Test
    void findByFindingOnAFindingWithNoNotesIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByFinding(findingId));
    }

    @Test
    void findByScanOnAScanWhoseFindingsHaveNoNotesIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByScan(scanId));
    }

    @Test
    void findByScanOnAnIdThatIsNotAScanAtAllIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByScan(9999L));
    }

    // --- malformed / failure -----------------------------------------------------------------

    @Test
    void insertAgainstAnUnknownFindingIdFailsOnTheForeignKey() {
        assertThrows(PersistenceException.class,
                () -> dao.insert(9999L, new NewAnnotation("note", NOW)));
    }

    @Test
    void insertWithABlankBodyThrowsBeforeAnyIo() {
        assertThrows(IllegalArgumentException.class, () -> new NewAnnotation("   ", NOW));
    }

    @Test
    void insertRejectsNullAnnotation() {
        assertThrows(NullPointerException.class, () -> dao.insert(findingId, null));
    }

    // --- cascade: the only 2-hop cascade in the project --------------------------------------

    @Test
    void deletingTheScanRowRemovesItsFindingsAndTheirAnnotations() throws Exception {
        dao.insert(findingId, new NewAnnotation("will cascade", NOW));
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM scans WHERE id = ?")) {
            ps.setLong(1, scanId);
            ps.executeUpdate();
        }
        assertEquals(List.of(), dao.findByFinding(findingId));
        assertEquals(List.of(), findingDao.findByScan(scanId));
    }

    @Test
    void deletingAFindingRowRemovesItsAnnotations() throws Exception {
        dao.insert(findingId, new NewAnnotation("will cascade too", NOW));
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM findings WHERE id = ?")) {
            ps.setLong(1, findingId);
            ps.executeUpdate();
        }
        assertEquals(List.of(), dao.findByFinding(findingId));
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new AnnotationDao(null));
    }
}
