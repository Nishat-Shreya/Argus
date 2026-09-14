package com.argus.core;

import com.argus.db.FindingRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE {@code db}/{@code core} diff mapping (plan §3.2): {@code FindingRecord -&gt;
 * FindingSnapshot}, {@code FindingChange -&gt; FindingDelta}, {@code ScanDiff -&gt;
 * ScanDiffReport}. Package-private for the same reason as {@link ScanSummaries} — it is what
 * structurally prevents a {@code FindingRecord} from ever reaching {@code ui}.
 */
final class ScanDiffReports {

    private ScanDiffReports() {
    }

    static FindingSnapshot of(FindingRecord record) {
        Objects.requireNonNull(record, "record");
        return new FindingSnapshot(record.id(), record.type().name(), record.subject(),
                record.port(), record.state());
    }

    static FindingDelta of(FindingChange change) {
        Objects.requireNonNull(change, "change");
        return new FindingDelta(of(change.baseline()), of(change.current()));
    }

    static ScanDiffReport of(ScanDiff diff) {
        Objects.requireNonNull(diff, "diff");
        return new ScanDiffReport(mapRecords(diff.added()), mapRecords(diff.removed()),
                mapChanges(diff.changed()));
    }

    private static List<FindingSnapshot> mapRecords(List<FindingRecord> records) {
        List<FindingSnapshot> mapped = new ArrayList<>(records.size());
        for (FindingRecord record : records) {
            mapped.add(of(record));
        }
        return List.copyOf(mapped);
    }

    private static List<FindingDelta> mapChanges(List<FindingChange> changes) {
        List<FindingDelta> mapped = new ArrayList<>(changes.size());
        for (FindingChange change : changes) {
            mapped.add(of(change));
        }
        return List.copyOf(mapped);
    }
}
