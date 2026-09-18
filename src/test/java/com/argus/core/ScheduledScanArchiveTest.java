package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ScheduledScanArchive}, the read/write gateway for recurring scan schedules (P3-08). */
class ScheduledScanArchiveTest {

    @TempDir
    Path tempDir;

    @Test
    void atCreatesNoFile() {
        Path dbFile = tempDir.resolve("argus.db");
        ScheduledScanArchive.at(dbFile);
        assertFalse(Files.exists(dbFile));
    }

    @Test
    void atDefaultLocationSharesTheDatabaseFileWithScanArchive() {
        assertEquals(ScanArchive.atDefaultLocation().databaseFile(),
                ScheduledScanArchive.atDefaultLocation().databaseFile());
    }

    @Test
    void scheduleReturnsAnEnabledScheduleDueOneIntervalFromNow() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));

        Instant before = Instant.now();
        ScheduledScan schedule = archive.schedule("example.com", 15);
        Instant after = Instant.now();

        assertTrue(schedule.id() > 0);
        assertEquals("example.com", schedule.target());
        assertEquals(15, schedule.intervalMinutes());
        assertTrue(schedule.enabled());
        assertNull(schedule.lastRunAt());
        assertFalse(schedule.createdAt().isBefore(before));
        assertFalse(schedule.createdAt().isAfter(after));
        assertEquals(schedule.createdAt().plusSeconds(900), schedule.nextRunAt());
    }

    @Test
    void scheduleRejectsAZeroOrNegativeInterval() {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        assertThrows(IllegalArgumentException.class, () -> archive.schedule("example.com", 0));
        assertThrows(IllegalArgumentException.class, () -> archive.schedule("example.com", -5));
    }

    @Test
    void listReturnsEveryScheduleOldestFirst() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        archive.schedule("a.example.com", 15);
        archive.schedule("b.example.com", 30);

        List<ScheduledScan> all = archive.list();
        assertEquals(2, all.size());
        assertEquals("a.example.com", all.get(0).target());
        assertEquals("b.example.com", all.get(1).target());
    }

    @Test
    void dueExcludesADisabledScheduleAndAScheduleNotYetDue() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        ScheduledScan farFuture = archive.schedule("future.example.com", 999_999);
        ScheduledScan disabled = archive.schedule("disabled.example.com", 15);
        archive.setEnabled(disabled.id(), false);

        List<ScheduledScan> due = archive.due(Instant.now());
        assertEquals(List.of(), due);
    }

    @Test
    void dueIncludesAnEnabledScheduleWhoseNextRunHasPassed() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        ScheduledScan schedule = archive.schedule("example.com", 15);

        List<ScheduledScan> due = archive.due(schedule.nextRunAt().plusSeconds(1));
        assertEquals(1, due.size());
        assertEquals(schedule.id(), due.get(0).id());
    }

    @Test
    void setEnabledTogglesAndReturnsTrueThenFalseForAnUnknownId() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        ScheduledScan schedule = archive.schedule("example.com", 15);

        assertTrue(archive.setEnabled(schedule.id(), false));
        assertFalse(archive.list().get(0).enabled());
        assertFalse(archive.setEnabled(9999L, true));
    }

    @Test
    void recordRunAdvancesNextRunByTheSchedulesOwnInterval() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        ScheduledScan schedule = archive.schedule("example.com", 15);
        // Truncated to millis: storage round-trips through epoch-millis columns, so an
        // in-memory Instant with sub-millisecond precision would never compare equal to what
        // comes back from a later read.
        Instant ranAt = schedule.nextRunAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        assertTrue(archive.recordRun(schedule.id(), schedule.intervalMinutes(), ranAt));

        ScheduledScan updated = archive.list().get(0);
        assertEquals(ranAt, updated.lastRunAt());
        assertEquals(ranAt.plusSeconds(900), updated.nextRunAt());
    }

    @Test
    void recordRunReturnsFalseForAnUnknownId() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        assertFalse(archive.recordRun(9999L, 15, Instant.now()));
    }

    @Test
    void deleteReturnsTrueThenFalse() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        ScheduledScan schedule = archive.schedule("example.com", 15);

        assertTrue(archive.delete(schedule.id()));
        assertEquals(List.of(), archive.list());
        assertFalse(archive.delete(schedule.id()));
    }

    @Test
    void returnedListsAreImmutable() throws Exception {
        ScheduledScanArchive archive = ScheduledScanArchive.at(tempDir.resolve("argus.db"));
        archive.schedule("example.com", 15);

        List<ScheduledScan> all = archive.list();
        assertThrows(UnsupportedOperationException.class,
                () -> all.add(all.get(0)));
    }

}
