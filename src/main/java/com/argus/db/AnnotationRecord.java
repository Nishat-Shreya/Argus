package com.argus.db;

import java.time.Instant;

/** A persisted {@code annotations} row. */
public record AnnotationRecord(long id, long findingId, String body, Instant createdAt) { }
