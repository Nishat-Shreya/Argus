package com.argus.core;

import com.argus.db.IntelResultRecord;
import java.util.Objects;

/** THE {@code db}/{@code core} intel-result mapping. Package-private, the {@code
 *  FindingTags}/{@code ScheduledScans} precedent. */
final class IntelResults {

    private IntelResults() {
    }

    static PersistedIntelResult of(IntelResultRecord record) {
        Objects.requireNonNull(record, "record");
        return new PersistedIntelResult(
                record.scanId(), record.summary(), record.kevMatched(), record.createdAt());
    }
}
