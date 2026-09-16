package com.argus.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One persisted annotation, projected for callers outside {@code core}'s {@code db} reach (plan
 * §3.2). {@code id} and {@code findingId} are db row ids, preserved as plain {@code long}s (the
 * {@code FindingSnapshot} rule). {@code createdAt} is an {@code Instant}: this is a note's own
 * creation time, NOT a per-finding timestamp (plan §0.2) — an annotation is never diffed, never
 * scored, never an input to {@code ScanDiffEngine}, {@code KevScorer} or any {@code IntelSource}.
 */
public record FindingNote(long id, long findingId, String body, Instant createdAt) {

    public FindingNote {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
