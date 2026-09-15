package com.argus.ui;

import java.util.List;
import java.util.Objects;

/**
 * Everything the report screen shows, and everything the exported HTML document contains,
 * already formatted as {@code String}s (plan §3.1). Immutable, which is what makes publication
 * across the {@code Task} boundary safe rather than a race (P3-01's {@code Map.copyOf} lesson).
 * Carries NO {@code Instant} -- every timestamp is a display projection formatted once by
 * {@link Reports#model(com.argus.core.ScanSummary, List, java.time.ZoneId)}, so no new clock
 * read and no new timestamp-typed field is ever needed here (plan §5.4).
 */
record ReportModel(
        long scanId,
        String target,
        String startedAt,
        String finishedAt,
        String summaryLine,
        List<FindingRow> rows) {

    ReportModel {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(summaryLine, "summaryLine");
        Objects.requireNonNull(rows, "rows");
        rows = List.copyOf(rows);
    }

    int findingCount() {
        return rows.size();
    }
}
