package com.argus.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One recurring-scan schedule, projected for callers outside {@code core}'s {@code db} reach.
 * {@code lastRunAt} is null until the schedule has run for the first time.
 */
public record ScheduledScan(long id, String target, int intervalMinutes, boolean enabled,
        Instant createdAt, Instant lastRunAt, Instant nextRunAt) {

    public ScheduledScan {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
