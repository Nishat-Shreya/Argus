package com.argus.core;

import java.util.Objects;

/**
 * One persisted tag assignment, projected for callers outside {@code core}'s {@code db} reach.
 * {@code tagId} and {@code findingId} are db row ids, preserved as plain {@code long}s (the
 * {@code FindingSnapshot} rule). {@code name} is the stored casing.
 *
 * NO TIME COMPONENT — {@code finding_tags} has no timestamp (plan §0.2).
 */
public record FindingTag(long tagId, long findingId, String name) {

    public FindingTag {
        Objects.requireNonNull(name, "name must not be null");
    }
}
