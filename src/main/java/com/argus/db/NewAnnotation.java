package com.argus.db;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One operator-authored note to be inserted against a finding. Mirrors {@link NewFinding}: the
 * FK is a separate parameter to the DAO, not a record component, so the value type stays a pure
 * value.
 *
 * {@code createdAt} is supplied BY THE CALLER — this package reads no clock (plan §0.2). It is
 * truncated to milliseconds in the compact constructor so what this record shows is exactly what
 * will be stored and what will come back (the {@code NewScan} rule, plan §7.5).
 */
public record NewAnnotation(String body, Instant createdAt) {

    public NewAnnotation {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
    }
}
