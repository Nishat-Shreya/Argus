package com.argus.db;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A scan session to be inserted. Instants are truncated to milliseconds IN THE COMPACT
 * CONSTRUCTOR, so what this record shows is exactly what will be stored and what will come back
 * (plan §7.5).
 *
 * Invariant: {@code (status == RUNNING) == (finishedAt == null)}.
 */
public record NewScan(String target, Instant startedAt, Instant finishedAt, ScanStatus status) {

    public NewScan {
        Objects.requireNonNull(target, "target must not be null");
        if (target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");

        startedAt = startedAt.truncatedTo(ChronoUnit.MILLIS);
        if (finishedAt != null) {
            finishedAt = finishedAt.truncatedTo(ChronoUnit.MILLIS);
        }

        if ((status == ScanStatus.RUNNING) != (finishedAt == null)) {
            throw new IllegalArgumentException(
                    "(status == RUNNING) must equal (finishedAt == null): status=" + status
                            + ", finishedAt=" + finishedAt);
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "finishedAt must not precede startedAt: startedAt=" + startedAt
                            + ", finishedAt=" + finishedAt);
        }
    }

    public static NewScan running(String target, Instant startedAt) {
        return new NewScan(target, startedAt, null, ScanStatus.RUNNING);
    }

    public static NewScan finished(String target, Instant startedAt, Instant finishedAt,
            ScanStatus terminalStatus) {
        return new NewScan(target, startedAt, finishedAt, terminalStatus);
    }
}
