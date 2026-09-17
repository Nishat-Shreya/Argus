package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan §6.2: {@link TagDao} against a real {@code @TempDir} file, never {@code :memory:}. */
class TagDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private Database database;
    private TagDao dao;
    private ScanRepository scanRepository;
    private FindingDao findingDao;
    private long scanId;
    private long findingId;

    @BeforeEach
    void setUp() throws Exception {
        database = TempDatabases.open(tempDir);
        dao = new TagDao(database);
        scanRepository = new ScanRepository(database);
        findingDao = new FindingDao(database);
        scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
        findingDao.insertAll(scanId, List.of(NewFinding.port("example.com", 80, "OPEN")));
        findingId = findingDao.findByScan(scanId).get(0).id();
    }

    // --- happy path -----------------------------------------------------------------------

    @Test
    void assignReturnsARecordWithAGeneratedTagIdAndTheGivenFindingIdAndStoredName()
            throws Exception {
        FindingTagRecord record = dao.assign(findingId, new NewTag("prod"));
        assertTrue(record.tagId() > 0);
        assertEquals(findingId, record.findingId());
        assertEquals("prod", record.name());
    }

    @Test
    void findByFindingReturnsTheAssignedTag() throws Exception {
        dao.assign(findingId, new NewTag("prod"));
        List<FindingTagRecord> found = dao.findByFinding(findingId);
        assertEquals(1, found.size());
        assertEquals("prod", found.get(0).name());
    }

    @Test
    void twoDifferentTagsComeBackOrderedByNameTieBrokenByTagId() throws Exception {
        dao.assign(findingId, new NewTag("web"));
        dao.assign(findingId, new NewTag("aws"));

        List<FindingTagRecord> found = dao.findByFinding(findingId);
        assertEquals(2, found.size());
        assertEquals("aws", found.get(0).name());
        assertEquals("web", found.get(1).name());
    }

    @Test
    void assigningASecondDifferentTagReturnsTheCorrectTagIdForTheSecondTag() throws Exception {
        // The last_insert_rowid() trap (§0.5/1): a buggy implementation returns the first
        // tag's id here.
        FindingTagRecord first = dao.assign(findingId, new NewTag("prod"));
        FindingTagRecord second = dao.assign(findingId, new NewTag("external"));

        assertTrue(second.tagId() != first.tagId());
        assertEquals("external", second.name());
    }

    @Test
    void reassigningTheSameTagToTheSameFindingIsANoOp() throws Exception {
        FindingTagRecord first = dao.assign(findingId, new NewTag("prod"));
        FindingTagRecord again = dao.assign(findingId, new NewTag("prod"));

        assertEquals(first.tagId(), again.tagId());
        assertEquals(1, dao.findByFinding(findingId).size());
        assertEquals(1, countTagsRows());
    }

    @Test
    void assigningTheSameTagNameToTwoDifferentFindingsCreatesOneTagRowAndTwoFindingTagsRows()
            throws Exception {
        findingDao.insertAll(scanId, List.of(NewFinding.port("example.com", 443, "OPEN")));
        long secondFindingId = findingDao.findByScanAndType(scanId, FindingType.PORT).stream()
                .filter(f -> f.port() != null && f.port() == 443)
                .findFirst().orElseThrow().id();

        FindingTagRecord onFirst = dao.assign(findingId, new NewTag("prod"));
        FindingTagRecord onSecond = dao.assign(secondFindingId, new NewTag("prod"));

        assertEquals(onFirst.tagId(), onSecond.tagId());
        assertEquals(1, countTagsRows());
        assertEquals(2, countFindingTagsRows());
    }

    @Test
    void nocaseIdentityYieldsTheSameTagIdAndTheFirstWritersStoredCasing() throws Exception {
        findingDao.insertAll(scanId, List.of(NewFinding.port("example.com", 443, "OPEN")));
        long secondFindingId = findingDao.findByScanAndType(scanId, FindingType.PORT).stream()
                .filter(f -> f.port() != null && f.port() == 443)
                .findFirst().orElseThrow().id();

        FindingTagRecord first = dao.assign(findingId, new NewTag("Prod"));
        FindingTagRecord second = dao.assign(secondFindingId, new NewTag("prod"));

        assertEquals(first.tagId(), second.tagId());
        assertEquals("Prod", first.name());
        assertEquals("Prod", second.name());
    }

    @Test
    void findByScanOrdersByFindingThenNameThenTagIdAndExcludesOtherScans() throws Exception {
        findingDao.insertAll(scanId, List.of(NewFinding.subdomain("a.example.com")));
        long secondFindingId = findingDao.findByScanAndType(scanId, FindingType.SUBDOMAIN).get(0).id();

        long otherScanId = scanRepository.insert(NewScan.running("example.org", NOW)).id();
        findingDao.insertAll(otherScanId, List.of(NewFinding.subdomain("other.example.org")));
        long otherFindingId =
                findingDao.findByScanAndType(otherScanId, FindingType.SUBDOMAIN).get(0).id();

        dao.assign(secondFindingId, new NewTag("web"));
        dao.assign(findingId, new NewTag("prod"));
        dao.assign(otherFindingId, new NewTag("other"));

        List<FindingTagRecord> found = dao.findByScan(scanId);
        assertEquals(2, found.size());
        assertEquals(findingId, found.get(0).findingId());
        assertEquals(secondFindingId, found.get(1).findingId());
    }

    @Test
    void unassignReturnsTrueThenFalse() throws Exception {
        FindingTagRecord record = dao.assign(findingId, new NewTag("prod"));
        assertTrue(dao.unassign(findingId, record.tagId()));
        assertFalse(dao.unassign(findingId, record.tagId()));
    }

    @Test
    void unassignRemovesTheFindingTagsRowButLeavesTheTagsRowIntact() throws Exception {
        FindingTagRecord record = dao.assign(findingId, new NewTag("prod"));
        dao.unassign(findingId, record.tagId());

        assertEquals(0, dao.findByFinding(findingId).size());
        assertEquals(1, countTagsRows());

        FindingTagRecord reassigned = dao.assign(findingId, new NewTag("prod"));
        assertEquals(record.tagId(), reassigned.tagId());
    }

    @Test
    void aNameWithANonAsciiCharacterAndOfExactlyFortyCharactersRoundTripsByteIdentical()
            throws Exception {
        String prefix = "café-";
        String name = prefix + "x".repeat(40 - prefix.length());
        assertEquals(40, name.length());
        FindingTagRecord record = dao.assign(findingId, new NewTag(name));
        FindingTagRecord found = dao.findByFinding(findingId).get(0);
        assertEquals(name, found.name());
        assertEquals(record.name(), found.name());
    }

    // --- empty / absent ---------------------------------------------------------------------

    @Test
    void findByFindingOnAFindingWithNoTagsIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByFinding(findingId));
    }

    @Test
    void findByScanOnATaglessScanIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByScan(scanId));
    }

    @Test
    void findByScanOnAnIdThatIsNotAScanAtAllIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findByScan(9999L));
    }

    // --- malformed / failure -----------------------------------------------------------------

    @Test
    void assignAgainstAnUnknownFindingIdFailsOnTheForeignKey() {
        assertThrows(PersistenceException.class,
                () -> dao.assign(9999L, new NewTag("prod")));
    }

    @Test
    void assignWithABlankNameThrowsBeforeAnyIo() {
        assertThrows(IllegalArgumentException.class, () -> new NewTag("   "));
    }

    @Test
    void assignRejectsNullTag() {
        assertThrows(NullPointerException.class, () -> dao.assign(findingId, null));
    }

    // --- cascade: the project's second 2-hop cascade -----------------------------------------

    @Test
    void deletingTheScanRowRemovesItsFindingsAndTheirFindingTagsButLeavesTheTagsRows()
            throws Exception {
        dao.assign(findingId, new NewTag("will cascade"));
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM scans WHERE id = ?")) {
            ps.setLong(1, scanId);
            ps.executeUpdate();
        }
        assertEquals(List.of(), dao.findByFinding(findingId));
        assertEquals(List.of(), findingDao.findByScan(scanId));
        assertEquals(1, countTagsRows());
    }

    @Test
    void deletingAFindingRowRemovesItsFindingTags() throws Exception {
        dao.assign(findingId, new NewTag("will cascade too"));
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM findings WHERE id = ?")) {
            ps.setLong(1, findingId);
            ps.executeUpdate();
        }
        assertEquals(List.of(), dao.findByFinding(findingId));
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new TagDao(null));
    }

    // --- helpers ---------------------------------------------------------------------------

    private int countTagsRows() throws Exception {
        return countRows("SELECT COUNT(*) FROM tags");
    }

    private int countFindingTagsRows() throws Exception {
        return countRows("SELECT COUNT(*) FROM finding_tags");
    }

    private int countRows(String sql) throws Exception {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
