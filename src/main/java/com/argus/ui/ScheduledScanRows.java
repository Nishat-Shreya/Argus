package com.argus.ui;

import com.argus.core.ScheduledScan;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code core.ScheduledScan -> ScheduledScanRow}, the display-formatting boundary (the {@code
 * FindingRows} / {@code Notes.entries} precedent). {@code ZoneId} is passed in, never read here.
 */
final class ScheduledScanRows {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ScheduledScanRows() {
    }

    static ScheduledScanRow of(ScheduledScan schedule, ZoneId zone) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(zone, "zone");
        return new ScheduledScanRow(
                schedule.id(),
                schedule.target(),
                "every " + schedule.intervalMinutes() + " min",
                schedule.enabled() ? "enabled" : "disabled",
                schedule.lastRunAt() == null
                        ? "never" : TIMESTAMP_FORMAT.format(schedule.lastRunAt().atZone(zone)),
                schedule.nextRunAt() == null
                        ? "—" : TIMESTAMP_FORMAT.format(schedule.nextRunAt().atZone(zone)));
    }

    static List<ScheduledScanRow> of(List<ScheduledScan> schedules, ZoneId zone) {
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(zone, "zone");
        List<ScheduledScanRow> rows = new ArrayList<>(schedules.size());
        for (ScheduledScan schedule : schedules) {
            rows.add(of(schedule, zone));
        }
        return List.copyOf(rows);
    }
}
