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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan §6.3: {@link AnnotationArchive}, the read/write gateway for finding annotations. */
class AnnotationArchiveTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private long seededScanId;

    @Test
    void atCreatesNoFile() {
        Path dbFile = tempDir.resolve("argus.db");
        AnnotationArchive.at(dbFile);
        assertFalse(Files.exists(dbFile));
    }

    @Test
    void atDefaultLocationSharesTheDatabaseFileWithScanArchive() {
        assertEquals(ScanArchive.atDefaultLocation().databaseFile(),
                AnnotationArchive.atDefaultLocation().databaseFile());
    }

    @Test
    void addThenListForFindingReturnsTheExactFixedInstant() throws Exception {
        long findingId = seedFinding();
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));

        archive.add(findingId, "false positive", FIXED_INSTANT);
        List<FindingNote> notes = archive.listForFinding(findingId);

        assertEquals(1, notes.size());
        assertEquals(FIXED_INSTANT, notes.get(0).createdAt());
    }

    @Test
    void addThenListForScanGroupsUnderTheRightFinding() throws Exception {
        long findingId = seedFinding();
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));

        archive.add(findingId, "note", FIXED_INSTANT);
        List<FindingNote> notes = archive.listForScan(seededScanId);

        assertEquals(1, notes.size());
        assertEquals(findingId, notes.get(0).findingId());
    }

    @Test
    void addAgainstAnUnknownFindingIdWrapsThePersistenceException() {
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));
        ScanArchiveException thrown = assertThrows(ScanArchiveException.class,
                () -> archive.add(9999L, "note", FIXED_INSTANT));
        assertTrue(thrown.getCause() instanceof com.argus.db.PersistenceException);
    }

    @Test
    void addWithABlankBodyPropagatesIllegalArgumentExceptionUnwrapped() {
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));
        assertThrows(IllegalArgumentException.class,
                () -> archive.add(1L, "   ", FIXED_INSTANT));
    }

    @Test
    void deleteReturnsTrueThenFalse() throws Exception {
        long findingId = seedFinding();
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));
        FindingNote added = archive.add(findingId, "note", FIXED_INSTANT);

        assertTrue(archive.delete(added.id()));
        assertFalse(archive.delete(added.id()));
    }

    @Test
    void listsOnAnEmptyDatabaseAreEmptyNotAnException() throws Exception {
        long findingId = seedFinding();
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));

        assertTrue(archive.listForFinding(findingId).isEmpty());
        assertTrue(archive.listForScan(seededScanId).isEmpty());
    }

    @Test
    void returnedListsAreImmutable() throws Exception {
        long findingId = seedFinding();
        AnnotationArchive archive = AnnotationArchive.at(tempDir.resolve("argus.db"));
        archive.add(findingId, "note", FIXED_INSTANT);

        List<FindingNote> notes = archive.listForFinding(findingId);
        assertThrows(UnsupportedOperationException.class,
                () -> notes.add(new FindingNote(99L, findingId, "x", FIXED_INSTANT)));
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
