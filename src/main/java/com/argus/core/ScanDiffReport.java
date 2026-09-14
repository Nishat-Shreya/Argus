package com.argus.core;

import java.util.List;
import java.util.Objects;

/**
 * The projection of {@link ScanDiff} (plan §3.1). Ordering is inherited from {@code ScanDiff}
 * and is contractual: {@code added} and {@code changed} follow the current list's order,
 * {@code removed} follows the baseline list's order.
 */
public record ScanDiffReport(List<FindingSnapshot> added, List<FindingSnapshot> removed,
        List<FindingDelta> changed) {

    public ScanDiffReport {
        Objects.requireNonNull(added, "added must not be null");
        Objects.requireNonNull(removed, "removed must not be null");
        Objects.requireNonNull(changed, "changed must not be null");
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }

    /** True when the two scans were identical. */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }
}
