package com.argus.ui;

import java.util.Objects;

/**
 * One position on a target's timeline axis (plan §3.1). {@code label} is a pre-formatted
 * {@code "2026-09-14 14:32:07"} — date AND time, formatted once during prefetch in the
 * caller-supplied zone. No {@code Instant} component: ordering is settled before projection
 * (plan §3.3), so this record carries only what the screen renders.
 */
record TimelinePoint(long scanId, String target, String label) {

    TimelinePoint {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(label, "label");
    }
}
