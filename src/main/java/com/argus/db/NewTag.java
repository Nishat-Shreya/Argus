package com.argus.db;

import java.util.Objects;

/**
 * One operator-authored tag name to be assigned to a finding. Mirrors {@code NewAnnotation}/
 * {@code NewFinding}: the FK is a separate parameter to the DAO, not a record component, so the
 * value type stays a pure value.
 *
 * This record reads no clock and holds no time — {@code finding_tags} has no timestamp column
 * (plan §0.2).
 */
public record NewTag(String name) {

    public NewTag {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
