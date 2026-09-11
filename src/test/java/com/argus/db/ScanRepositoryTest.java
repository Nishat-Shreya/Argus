package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ScanRepository}'s scan-only surface (plan §6.4): insert/find/finish/findAll. */
class ScanRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123Z");

    @TempDir
    Path tempDir;

    private ScanRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Database database = TempDatabases.open(tempDir);
        repository = new ScanRepository(database);
    }

    @Test
    void insertReturnsTheGeneratedId() throws Exception {
        ScanRecord first = repository.insert(NewScan.running("example.com", NOW));
        ScanRecord second = repository.insert(NewScan.running("example.org", NOW));

        assertTrue(first.id() > 0);
        assertTrue(second.id() > 0);
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void insertedScanIsReadBackFieldForField() throws Exception {
        Instant finishedAt = NOW.plusSeconds(30);
        NewScan newScan = NewScan.finished("example.com", NOW, finishedAt, ScanStatus.COMPLETED);
        ScanRecord inserted = repository.insert(newScan);

        ScanRecord read = repository.findById(inserted.id()).orElseThrow();
        assertEquals("example.com", read.target());
        assertEquals(newScan.startedAt(), read.startedAt());
        assertEquals(newScan.finishedAt(), read.finishedAt());
        assertEquals(ScanStatus.COMPLETED, read.status());
    }

    @Test
    void runningScanHasNullFinishedAtOnReadBack() throws Exception {
        ScanRecord inserted = repository.insert(NewScan.running("example.com", NOW));
        ScanRecord read = repository.findById(inserted.id()).orElseThrow();
        assertNull(read.finishedAt());
    }

    @Test
    void instantsRoundTripToMillisecondPrecision() throws Exception {
        Instant withMillis = Instant.parse("2026-01-01T00:00:00.789Z");
        ScanRecord inserted = repository.insert(NewScan.running("example.com", withMillis));
        ScanRecord read = repository.findById(inserted.id()).orElseThrow();
        assertEquals(withMillis.truncatedTo(ChronoUnit.MILLIS), read.startedAt());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() throws Exception {
        assertEquals(Optional.empty(), repository.findById(9999L));
    }

    @Test
    void finishSetsTheTerminalStatusAndTime() throws Exception {
        ScanRecord inserted = repository.insert(NewScan.running("example.com", NOW));
        Instant finishedAt = NOW.plusSeconds(10);

        ScanRecord finished = repository.finish(inserted.id(), finishedAt, ScanStatus.COMPLETED);
        assertEquals(ScanStatus.COMPLETED, finished.status());
        assertEquals(finishedAt.truncatedTo(ChronoUnit.MILLIS), finished.finishedAt());

        ScanRecord reread = repository.findById(inserted.id()).orElseThrow();
        assertEquals(ScanStatus.COMPLETED, reread.status());
        assertEquals(finishedAt.truncatedTo(ChronoUnit.MILLIS), reread.finishedAt());
    }

    @Test
    void finishRejectsRunningAsAStatus() throws Exception {
        ScanRecord inserted = repository.insert(NewScan.running("example.com", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> repository.finish(inserted.id(), NOW.plusSeconds(1), ScanStatus.RUNNING));

        ScanRecord reread = repository.findById(inserted.id()).orElseThrow();
        assertTrue(reread.isRunning());
    }

    @Test
    void finishRejectsAFinishTimeBeforeTheStoredStart() throws Exception {
        ScanRecord inserted = repository.insert(NewScan.running("example.com", NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.finish(
                inserted.id(), NOW.minusSeconds(1), ScanStatus.COMPLETED));

        ScanRecord reread = repository.findById(inserted.id()).orElseThrow();
        assertTrue(reread.isRunning());
    }

    @Test
    void finishOfAnUnknownScanThrows() {
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> repository.finish(9999L, NOW.plusSeconds(1), ScanStatus.COMPLETED));
        assertTrue(e.getMessage().contains("9999"), "message should name the id: " + e.getMessage());
    }

    @Test
    void findAllIsNewestFirst() throws Exception {
        ScanRecord first = repository.insert(NewScan.running("a.example.com", NOW));
        ScanRecord second = repository.insert(NewScan.running("b.example.com", NOW.plusSeconds(1)));
        ScanRecord third = repository.insert(NewScan.running("c.example.com", NOW.plusSeconds(2)));

        List<ScanRecord> all = repository.findAll();
        assertEquals(List.of(third.id(), second.id(), first.id()),
                all.stream().map(ScanRecord::id).toList());
    }

    @Test
    void findAllOnAnEmptyDatabaseReturnsEmptyList() throws Exception {
        assertEquals(List.of(), repository.findAll());
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new ScanRepository(null));
    }

    @Test
    void targetIsStoredVerbatimAndNotValidatedAsADomain() throws Exception {
        ScanRecord inserted = repository.insert(NewScan.running("not a domain", NOW));
        ScanRecord read = repository.findById(inserted.id()).orElseThrow();
        assertEquals("not a domain", read.target());
    }

    @Test
    void blankTargetIsStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> NewScan.running("   ", NOW));
    }
}
