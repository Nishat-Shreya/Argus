package com.argus.core;

import com.argus.db.ScheduledScanRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE {@code db}/{@code core} scheduled-scan mapping: {@code ScheduledScanRecord ->
 * ScheduledScan}, including parsing the stored {@code cron_expression} text back into a plain
 * interval-in-minutes. Package-private for the same structural reason as {@code FindingTags} /
 * {@code FindingNotes} -- it is what structurally prevents a {@code ScheduledScanRecord} from
 * ever reaching {@code ui}.
 */
final class ScheduledScans {

    private ScheduledScans() {
    }

    static ScheduledScan of(ScheduledScanRecord record) {
        Objects.requireNonNull(record, "record");
        int intervalMinutes = Integer.parseInt(record.cronExpression());
        return new ScheduledScan(record.id(), record.target(), intervalMinutes, record.enabled(),
                record.createdAt(), record.lastRunAt(), record.nextRunAt());
    }

    static List<ScheduledScan> of(List<ScheduledScanRecord> records) {
        Objects.requireNonNull(records, "records");
        List<ScheduledScan> mapped = new ArrayList<>(records.size());
        for (ScheduledScanRecord record : records) {
            mapped.add(of(record));
        }
        return List.copyOf(mapped);
    }
}
