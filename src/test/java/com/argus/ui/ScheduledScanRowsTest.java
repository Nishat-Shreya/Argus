package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.core.ScheduledScan;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledScanRowsTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEXT_RUN = Instant.parse("2026-01-01T00:15:00Z");

    @Test
    void formatsAnEnabledScheduleThatHasNeverRun() {
        ScheduledScan schedule =
                new ScheduledScan(1L, "example.com", 15, true, CREATED_AT, null, NEXT_RUN);

        ScheduledScanRow row = ScheduledScanRows.of(schedule, ZONE);
        assertEquals(1L, row.id());
        assertEquals("example.com", row.target());
        assertEquals("every 15 min", row.interval());
        assertEquals("enabled", row.enabledText());
        assertEquals("never", row.lastRun());
        assertEquals("2026-01-01 00:15", row.nextRun());
    }

    @Test
    void formatsADisabledScheduleThatHasRun() {
        Instant lastRun = Instant.parse("2026-01-01T00:00:00Z");
        ScheduledScan schedule =
                new ScheduledScan(1L, "example.com", 30, false, CREATED_AT, lastRun, NEXT_RUN);

        ScheduledScanRow row = ScheduledScanRows.of(schedule, ZONE);
        assertEquals("disabled", row.enabledText());
        assertEquals("2026-01-01 00:00", row.lastRun());
    }

    @Test
    void formatsAnEmDashWhenNextRunIsAbsent() {
        ScheduledScan schedule =
                new ScheduledScan(1L, "example.com", 15, false, CREATED_AT, null, null);

        ScheduledScanRow row = ScheduledScanRows.of(schedule, ZONE);
        assertEquals("—", row.nextRun());
    }

    @Test
    void mapsAListPreservingOrder() {
        List<ScheduledScan> schedules = List.of(
                new ScheduledScan(1L, "a.example.com", 15, true, CREATED_AT, null, NEXT_RUN),
                new ScheduledScan(2L, "b.example.com", 30, true, CREATED_AT, null, NEXT_RUN));

        List<ScheduledScanRow> rows = ScheduledScanRows.of(schedules, ZONE);
        assertEquals(2, rows.size());
        assertEquals("a.example.com", rows.get(0).target());
        assertEquals("b.example.com", rows.get(1).target());
    }
}
