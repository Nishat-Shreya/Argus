package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.db.ScheduledScanRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code db}/{@code core} scheduled-scan mapping, including {@code cron_expression}
 *  parsing back into a plain interval-in-minutes. */
class ScheduledScansTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void mapsAllFieldsAndParsesTheIntervalFromCronExpression() {
        ScheduledScanRecord record =
                new ScheduledScanRecord(1L, "example.com", "15", true, NOW, null, NOW);

        ScheduledScan mapped = ScheduledScans.of(record);
        assertEquals(1L, mapped.id());
        assertEquals("example.com", mapped.target());
        assertEquals(15, mapped.intervalMinutes());
        assertEquals(true, mapped.enabled());
        assertEquals(NOW, mapped.createdAt());
        assertNull(mapped.lastRunAt());
        assertEquals(NOW, mapped.nextRunAt());
    }

    @Test
    void mapsAListPreservingOrder() {
        List<ScheduledScanRecord> records = List.of(
                new ScheduledScanRecord(1L, "a.example.com", "15", true, NOW, null, NOW),
                new ScheduledScanRecord(2L, "b.example.com", "30", true, NOW, null, NOW));

        List<ScheduledScan> mapped = ScheduledScans.of(records);
        assertEquals(2, mapped.size());
        assertEquals("a.example.com", mapped.get(0).target());
        assertEquals("b.example.com", mapped.get(1).target());
    }

    @Test
    void rejectsNullRecord() {
        assertThrows(NullPointerException.class, () -> ScheduledScans.of((ScheduledScanRecord) null));
    }
}
