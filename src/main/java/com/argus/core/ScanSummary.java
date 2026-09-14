package com.argus.core;

import com.argus.db.ScanStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * A persisted scan, projected for callers outside {@code core}'s {@code db} reach (plan §3.1).
 * No {@code db} type appears in any component — {@code status} crosses the boundary as the
 * opaque {@code String} token {@code ScanStatus.name()} produces, mirroring P1-04's "state is
 * an opaque token to db" ruling in reverse.
 */
public record ScanSummary(long id, String target, Instant startedAt, Instant finishedAt,
        String status) {

    public ScanSummary {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        // finishedAt MAY be null: a RUNNING scan has none.
    }

    /**
     * True only for a scan whose finding set can be trusted as complete (plan §4.2). This is
     * the ONE place the {@code "COMPLETED"} vocabulary literal exists — {@code ui} never sees
     * {@link ScanStatus}, only this boolean.
     */
    public boolean complete() {
        return ScanStatus.COMPLETED.name().equals(status);
    }
}
