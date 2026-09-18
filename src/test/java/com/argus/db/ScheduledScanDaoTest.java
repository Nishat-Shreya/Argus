package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ScheduledScanDao} against a real {@code @TempDir} file, never {@code :memory:}. */
class ScheduledScanDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEXT_RUN = NOW.plusSeconds(900);

    @TempDir
    Path tempDir;

    private ScheduledScanDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new ScheduledScanDao(TempDatabases.open(tempDir));
    }

    @Test
    void insertReturnsARecordWithAGeneratedIdEnabledAndNoLastRunYet() throws Exception {
        ScheduledScanRecord record =
                dao.insert(new NewScheduledScan("example.com", "15", NOW, NEXT_RUN));

        assertTrue(record.id() > 0);
        assertEquals("example.com", record.target());
        assertEquals("15", record.cronExpression());
        assertTrue(record.enabled());
        assertEquals(NOW, record.createdAt());
        assertNull(record.lastRunAt());
        assertEquals(NEXT_RUN, record.nextRunAt());
    }

    @Test
    void findAllOnAnEmptyTableIsAnEmptyList() throws Exception {
        assertEquals(List.of(), dao.findAll());
    }

    @Test
    void findAllReturnsInsertedSchedulesOrderedById() throws Exception {
        dao.insert(new NewScheduledScan("a.example.com", "15", NOW, NEXT_RUN));
        dao.insert(new NewScheduledScan("b.example.com", "30", NOW, NEXT_RUN));

        List<ScheduledScanRecord> all = dao.findAll();
        assertEquals(2, all.size());
        assertEquals("a.example.com", all.get(0).target());
        assertEquals("b.example.com", all.get(1).target());
    }

    @Test
    void setEnabledTogglesAndReturnsTrueThenFalseForAnUnknownId() throws Exception {
        ScheduledScanRecord record =
                dao.insert(new NewScheduledScan("example.com", "15", NOW, NEXT_RUN));

        assertTrue(dao.setEnabled(record.id(), false));
        assertFalse(dao.findAll().get(0).enabled());
        assertFalse(dao.setEnabled(9999L, true));
    }

    @Test
    void recordRunUpdatesLastRunAndNextRunAndReturnsFalseForAnUnknownId() throws Exception {
        ScheduledScanRecord record =
                dao.insert(new NewScheduledScan("example.com", "15", NOW, NEXT_RUN));
        Instant ranAt = NEXT_RUN;
        Instant newNextRunAt = ranAt.plusSeconds(900);

        assertTrue(dao.recordRun(record.id(), ranAt, newNextRunAt));

        ScheduledScanRecord updated = dao.findAll().get(0);
        assertEquals(ranAt, updated.lastRunAt());
        assertEquals(newNextRunAt, updated.nextRunAt());
        assertFalse(dao.recordRun(9999L, ranAt, newNextRunAt));
    }

    @Test
    void deleteReturnsTrueThenFalse() throws Exception {
        ScheduledScanRecord record =
                dao.insert(new NewScheduledScan("example.com", "15", NOW, NEXT_RUN));

        assertTrue(dao.delete(record.id()));
        assertEquals(List.of(), dao.findAll());
        assertFalse(dao.delete(record.id()));
    }

    @Test
    void constructorRejectsNullDatabase() {
        assertThrows(NullPointerException.class, () -> new ScheduledScanDao(null));
    }

    @Test
    void insertRejectsNullSchedule() {
        assertThrows(NullPointerException.class, () -> dao.insert(null));
    }
}
