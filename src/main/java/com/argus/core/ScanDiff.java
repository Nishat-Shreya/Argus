package com.argus.core;

import com.argus.db.FindingRecord;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of comparing two scans. Immutable; all three lists are defensively copied and
 * unmodifiable (the {@code com.argus.db.ScanSession} precedent).
 *
 * ORDERING IS PART OF THE CONTRACT (plan §4.3): {@code added} and {@code changed} follow the
 * order of the CURRENT list; {@code removed} follows the order of the BASELINE list. Since
 * {@code FindingDao.findByScan} is {@code ORDER BY id}, that means insertion order in practice,
 * and it makes the diff byte-for-byte reproducible for a given pair of inputs.
 *
 * Carries NO timestamp, NO scan ids, and NO clock reading of any kind (plan §4.5).
 */
public record ScanDiff(List<FindingRecord> added, List<FindingRecord> removed,
        List<FindingChange> changed) {

    public ScanDiff {
        Objects.requireNonNull(added, "added must not be null");
        Objects.requireNonNull(removed, "removed must not be null");
        Objects.requireNonNull(changed, "changed must not be null");
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }

    /** True when the two scans were identical: the acceptance criterion, as one call. */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }
}
