package com.argus.ui;

import java.util.List;

/**
 * The result of parsing one dropped blob. {@code accepted} holds normalized domains in
 * first-seen order with in-blob duplicates already removed; {@code rejected} holds the
 * offending raw tokens, each truncated to {@link DroppedTargets#MAX_ECHO_CHARS} so a hostile
 * blob cannot produce a multi-kilobyte status label. Toolkit-free (plan §3.2).
 */
record TargetDrop(List<String> accepted, List<String> rejected) {

    TargetDrop {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    static TargetDrop empty() {
        return new TargetDrop(List.of(), List.of());
    }

    /** True when {@code accepted} and {@code rejected} are both empty. */
    boolean isEmpty() {
        return accepted.isEmpty() && rejected.isEmpty();
    }
}
