package com.argus.core;

import com.argus.db.FindingRecord;
import java.util.Objects;

/**
 * The same finding (same {@link FindingKey}) seen in both scans with a different {@code state} —
 * e.g. port 80 going {@code OPEN -> FILTERED}.
 *
 * Self-validating: the two records MUST share a key and MUST NOT share a state. A
 * {@code FindingChange} that reports no change is unconstructible, so an engine bug becomes a
 * construction failure rather than a misleading UI row (plan §3.2).
 *
 * Carries whole {@link FindingRecord}s, not just the two state strings, so both {@code id}s
 * survive: P2-09 renders {@code 80  OPEN -> FILTERED} directly, and a later "annotate this diff
 * row" feature keyed by {@code finding_id} needs the id it refers to.
 */
public record FindingChange(FindingRecord baseline, FindingRecord current) {

    public FindingChange {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(current, "current must not be null");

        FindingKey baselineKey = FindingKey.of(baseline);
        FindingKey currentKey = FindingKey.of(current);
        if (!baselineKey.equals(currentKey)) {
            throw new IllegalArgumentException(
                    "baseline and current must share a FindingKey: baseline=" + baselineKey
                            + ", current=" + currentKey);
        }
        if (Objects.equals(baseline.state(), current.state())) {
            throw new IllegalArgumentException(
                    "a FindingChange must report an actual state change, both sides had state="
                            + baseline.state());
        }
    }

    /** The identity shared by both sides. */
    public FindingKey key() {
        return FindingKey.of(baseline);
    }
}
