package com.argus.db;

import java.time.Instant;

/**
 * A persisted {@code scheduled_scans} row. {@code lastRunAt} is null until the schedule has run
 * for the first time; {@code nextRunAt} is set at creation and after every run.
 */
public record ScheduledScanRecord(long id, String target, String cronExpression, boolean enabled,
        Instant createdAt, Instant lastRunAt, Instant nextRunAt) {
}
