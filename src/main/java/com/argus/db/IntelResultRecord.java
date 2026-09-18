package com.argus.db;

import java.time.Instant;

/** A persisted {@code scan_intel} row -- at most one per scan (P3-16). */
public record IntelResultRecord(long id, long scanId, String summary, boolean kevMatched,
        Instant createdAt) {
}
