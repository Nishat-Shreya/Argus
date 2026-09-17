package com.argus.core;

import com.argus.db.FindingTagRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE {@code db}/{@code core} tag mapping: {@code FindingTagRecord -> FindingTag}.
 * Package-private for the same structural reason as {@code FindingNotes} / {@code ScanSummaries}
 * / {@code ScanDiffReports} / {@code NewFindings} — it is what structurally prevents a
 * {@code FindingTagRecord} from ever reaching {@code ui}.
 */
final class FindingTags {

    private FindingTags() {
    }

    static FindingTag of(FindingTagRecord record) {
        Objects.requireNonNull(record, "record");
        return new FindingTag(record.tagId(), record.findingId(), record.name());
    }

    static List<FindingTag> of(List<FindingTagRecord> records) {
        Objects.requireNonNull(records, "records");
        List<FindingTag> mapped = new ArrayList<>(records.size());
        for (FindingTagRecord record : records) {
            mapped.add(of(record));
        }
        return List.copyOf(mapped);
    }
}
