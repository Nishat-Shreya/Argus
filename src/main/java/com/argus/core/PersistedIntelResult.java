package com.argus.core;

import java.time.Instant;
import java.util.Objects;

/** One scan's persisted intel/KEV enrichment result, projected for callers outside {@code
 *  core}'s {@code db} reach. */
public record PersistedIntelResult(long scanId, String summary, boolean kevMatched,
        Instant createdAt) {

    public PersistedIntelResult {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
