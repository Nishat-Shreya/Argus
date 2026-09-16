package com.argus.core;

import com.argus.db.AnnotationRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE {@code db}/{@code core} annotation mapping (plan §3.2): {@code AnnotationRecord -&gt;
 * FindingNote}. Package-private for the same structural reason as {@code ScanSummaries} /
 * {@code ScanDiffReports} / {@code NewFindings} — it is what structurally prevents an
 * {@code AnnotationRecord} from ever reaching {@code ui}.
 */
final class FindingNotes {

    private FindingNotes() {
    }

    static FindingNote of(AnnotationRecord record) {
        Objects.requireNonNull(record, "record");
        return new FindingNote(record.id(), record.findingId(), record.body(), record.createdAt());
    }

    static List<FindingNote> of(List<AnnotationRecord> records) {
        Objects.requireNonNull(records, "records");
        List<FindingNote> mapped = new ArrayList<>(records.size());
        for (AnnotationRecord record : records) {
            mapped.add(of(record));
        }
        return List.copyOf(mapped);
    }
}
