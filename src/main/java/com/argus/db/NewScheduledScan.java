package com.argus.db;

import java.time.Instant;
import java.util.Objects;

/**
 * One new recurring-scan schedule to persist. {@code cronExpression} stores a plain positive
 * integer as text -- the interval in minutes between runs. This project does not implement a
 * real cron parser: a single-operator desktop tool only needs "run this again every N minutes
 * while the app is open," and the column's existing {@code CHECK (length(cron_expression) > 0)}
 * places no format requirement on it.
 */
public record NewScheduledScan(String target, String cronExpression, Instant createdAt,
        Instant nextRunAt) {

    public NewScheduledScan {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(cronExpression, "cronExpression must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(nextRunAt, "nextRunAt must not be null");
        if (target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (cronExpression.isBlank()) {
            throw new IllegalArgumentException("cronExpression must not be blank");
        }
    }
}
