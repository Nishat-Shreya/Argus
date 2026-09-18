package com.argus.db;

import java.time.Instant;
import java.util.Objects;

/** One new intel/KEV enrichment result to persist against a scan (P3-16). */
public record NewIntelResult(String summary, boolean kevMatched, Instant createdAt) {

    public NewIntelResult {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }
}
