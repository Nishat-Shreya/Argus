package com.argus.core;

import java.util.Objects;

/**
 * The projection of {@link FindingChange}: the same finding, two states (plan §3.1). Null
 * checks only — validity (same key, differing state) is {@code FindingChange}'s job and is not
 * re-implemented here (plan §7.3).
 */
public record FindingDelta(FindingSnapshot baseline, FindingSnapshot current) {

    public FindingDelta {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(current, "current must not be null");
    }
}
