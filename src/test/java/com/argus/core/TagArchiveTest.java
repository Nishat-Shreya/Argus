package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.Database;
import com.argus.db.FindingDao;
import com.argus.db.NewFinding;
import com.argus.db.NewScan;
import com.argus.db.ScanRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan §6.3: {@link TagArchive}, the read/write gateway for finding tags. */
class TagArchiveTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private long seededScanId;

    @Test
    void atCreatesNoFile() {
        Path dbFile = tempDir.resolve("argus.db");
        TagArchive.at(dbFile);
        assertFalse(Files.exists(dbFile));
    }

    @Test
    void atDefaultLocationSharesTheDatabaseFileWithScanArchive() {
        assertEquals(ScanArchive.atDefaultLocation().databaseFile(),
                TagArchive.atDefaultLocation().databaseFile());
    }

    @Test
    void addThenListForFindingReturnsTheTag() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));

        FindingTag added = archive.add(findingId, "prod");
        assertTrue(added.tagId() > 0);
        List<FindingTag> tags = archive.listForFinding(findingId);

        assertEquals(1, tags.size());
        assertEquals("prod", tags.get(0).name());
    }

    @Test
    void addTwiceWithTheSameNameIsOneTagWithTheSameTagId() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));

        FindingTag first = archive.add(findingId, "prod");
        FindingTag second = archive.add(findingId, "prod");

        assertEquals(first.tagId(), second.tagId());
        assertEquals(1, archive.listForFinding(findingId).size());
    }

    @Test
    void addThenListForScanGroupsTheAssignmentUnderTheRightFinding() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));

        archive.add(findingId, "prod");
        List<FindingTag> tags = archive.listForScan(seededScanId);

        assertEquals(1, tags.size());
        assertEquals(findingId, tags.get(0).findingId());
    }

    @Test
    void addAgainstAnUnknownFindingIdWrapsThePersistenceException() {
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));
        ScanArchiveException thrown = assertThrows(ScanArchiveException.class,
                () -> archive.add(9999L, "prod"));
        assertTrue(thrown.getCause() instanceof com.argus.db.PersistenceException);
    }

    @Test
    void addWithABlankNamePropagatesIllegalArgumentExceptionUnwrapped() {
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));
        assertThrows(IllegalArgumentException.class,
                () -> archive.add(1L, "   "));
    }

    @Test
    void removeReturnsTrueThenFalse() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));
        FindingTag added = archive.add(findingId, "prod");

        assertTrue(archive.remove(findingId, added.tagId()));
        assertFalse(archive.remove(findingId, added.tagId()));
    }

    @Test
    void listsOnAnEmptyDatabaseAreEmptyNotAnException() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));

        assertTrue(archive.listForFinding(findingId).isEmpty());
        assertTrue(archive.listForScan(seededScanId).isEmpty());
    }

    @Test
    void returnedListsAreImmutable() throws Exception {
        long findingId = seedFinding();
        TagArchive archive = TagArchive.at(tempDir.resolve("argus.db"));
        archive.add(findingId, "prod");

        List<FindingTag> tags = archive.listForFinding(findingId);
        assertThrows(UnsupportedOperationException.class,
                () -> tags.add(new FindingTag(99L, findingId, "x")));
    }

    @Test
    void sourceContainsNoClockReadAndFindingTagHasNoJavaTimeComponent() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/argus/core/TagArchive.java"));
        for (String forbidden : List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "System.currentTimeMillis")) {
            assertFalse(source.contains(forbidden),
                    "TagArchive.java must not contain a clock read (" + forbidden + ")");
        }
        assertTrue(FindingTag.class.getRecordComponents().length == 3);
    }

    private long seedFinding() throws Exception {
        Database database = Database.open(tempDir.resolve("argus.db"));
        ScanRepository scanRepository = new ScanRepository(database);
        FindingDao findingDao = new FindingDao(database);
        seededScanId = scanRepository.insert(NewScan.running("example.com", FIXED_INSTANT)).id();
        findingDao.insertAll(seededScanId, List.of(NewFinding.port("example.com", 80, "OPEN")));
        return findingDao.findByScan(seededScanId).get(0).id();
    }
}
