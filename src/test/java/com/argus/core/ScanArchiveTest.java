package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.Database;
import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import com.argus.db.PersistenceException;
import com.argus.db.ScanRecord;
import com.argus.db.ScanRepository;
import com.argus.db.ScanSession;
import com.argus.db.ScanStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T6's driver: {@link ScanArchive} — real {@code @TempDir} SQLite file, never {@code :memory:}
 *  (P1-04 standing rule). */
class ScanArchiveTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00.123Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00.456Z");

    @TempDir
    Path tempDir;

    @Test
    void happyPathRoundTrip() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        List<Object> findings = List.of(
                new PortResult("host1", 80, PortState.OPEN),
                new PortResult("host2", 443, PortState.CLOSED),
                new Subdomain("api.example.com"),
                new Subdomain("*.example.com"));
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, findings);

        long id = archive.save(run);

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        ScanSession session = repository.loadSession(id).orElseThrow();
        ScanRecord scanRecord = session.scan();
        assertEquals("example.com", scanRecord.target());
        assertEquals(STARTED, scanRecord.startedAt());
        assertEquals(FINISHED, scanRecord.finishedAt());
        assertEquals(ScanStatus.COMPLETED, scanRecord.status());

        List<FindingRecord> loaded = session.findings();
        assertEquals(4, loaded.size());
        assertEquals(FindingType.PORT, loaded.get(0).type());
        assertEquals("host1", loaded.get(0).subject());
        assertEquals(80, loaded.get(0).port());
        assertEquals("OPEN", loaded.get(0).state());
        assertEquals(FindingType.PORT, loaded.get(1).type());
        assertEquals("host2", loaded.get(1).subject());
        assertEquals(443, loaded.get(1).port());
        assertEquals("CLOSED", loaded.get(1).state());
        assertEquals(FindingType.SUBDOMAIN, loaded.get(2).type());
        assertEquals("api.example.com", loaded.get(2).subject());
        assertEquals(FindingType.SUBDOMAIN, loaded.get(3).type());
        assertEquals("*.example.com", loaded.get(3).subject());
    }

    @Test
    void constructionDoesNoIo() {
        Path nested = tempDir.resolve("sub").resolve("argus.db");
        ScanArchive archive = ScanArchive.at(nested);

        assertEquals(nested, archive.databaseFile());
        assertFalse(Files.exists(nested));
        assertFalse(Files.exists(nested.getParent()));
    }

    @Test
    void atDefaultLocationResolvesUnderAppDataDirectory() {
        Path expected = AppDataDirectory.resolve().resolve("argus.db");
        assertEquals(expected, ScanArchive.atDefaultLocation().databaseFile());
    }

    @Test
    void emptyFindingsIsNotAnError() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, List.of());

        long id = archive.save(run);

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        ScanSession session = repository.loadSession(id).orElseThrow();
        assertEquals(List.of(), session.findings());
    }

    @Test
    void twoSavesProduceTwoDistinctLoadableIdsNewestFirst() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        ScanRun first = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, List.of());
        ScanRun second = new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED, List.of());

        long firstId = archive.save(first);
        long secondId = archive.save(second);

        assertNotEquals(firstId, secondId);
        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        assertTrue(repository.loadSession(firstId).isPresent());
        assertTrue(repository.loadSession(secondId).isPresent());
        List<ScanRecord> all = repository.findAll();
        assertEquals(2, all.size());
        assertEquals(secondId, all.get(0).id());
        assertEquals(firstId, all.get(1).id());
    }

    @Test
    void cancelledRunIsStoredWithCancelledStatusAndPartialFindings() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.CANCELLED,
                List.of(new Subdomain("a.example.com")));

        long id = archive.save(run);

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        ScanSession session = repository.loadSession(id).orElseThrow();
        assertEquals(ScanStatus.CANCELLED, session.scan().status());
        assertEquals(1, session.findings().size());
    }

    @Test
    void aDuplicateFindingIdentityFailsTheWholeSaveAtomically() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        List<Object> findings = List.of(
                new PortResult("host", 80, PortState.OPEN),
                new PortResult("host", 80, PortState.OPEN));
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, findings);

        assertThrows(ScanArchiveException.class, () -> archive.save(run));

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        assertEquals(List.of(), repository.findAll());
    }

    @Test
    void theAtomicityFailureWrapsAPersistenceException() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        List<Object> findings = List.of(
                new PortResult("host", 80, PortState.OPEN),
                new PortResult("host", 80, PortState.OPEN));
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, findings);

        ScanArchiveException e = assertThrows(ScanArchiveException.class, () -> archive.save(run));
        assertNotNull(e.getCause());
        assertTrue(e.getCause() instanceof PersistenceException);
    }

    @Test
    void anUnwritableTargetThrowsScanArchiveExceptionNotARawDbException() throws IOException {
        Path asDirectory = tempDir.resolve("argus.db");
        Files.createDirectories(asDirectory);
        ScanArchive archive = ScanArchive.at(asDirectory);
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, List.of());

        assertThrows(ScanArchiveException.class, () -> archive.save(run));
    }

    @Test
    void saveOfNullThrowsNullPointerException() {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        assertThrows(NullPointerException.class, () -> archive.save(null));
    }
}
