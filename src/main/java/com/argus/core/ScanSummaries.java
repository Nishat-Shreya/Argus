package com.argus.core;

import com.argus.db.ScanRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE {@code db -&gt; core} scan session mapping (plan §3.2). Package-private is deliberate: it
 * is what structurally prevents a {@code ScanRecord} from ever reaching {@code ui} — the
 * {@code NewFindings}/{@code NewScans} pattern, applied in reverse.
 */
final class ScanSummaries {

    private ScanSummaries() {
    }

    static ScanSummary of(ScanRecord record) {
        Objects.requireNonNull(record, "record");
        return new ScanSummary(record.id(), record.target(), record.startedAt(),
                record.finishedAt(), record.status().name());
    }

    /** Preserves iteration order; returns an unmodifiable list. */
    static List<ScanSummary> of(List<ScanRecord> records) {
        Objects.requireNonNull(records, "records");
        List<ScanSummary> mapped = new ArrayList<>(records.size());
        for (ScanRecord record : records) {
            mapped.add(of(record));
        }
        return List.copyOf(mapped);
    }
}
