package com.argus.core;

import com.argus.db.NewScan;
import com.argus.db.ScanStatus;
import java.util.Objects;

/** THE core -&gt; db session mapping (§3.6). Split from {@link NewFindings} so each mapper is a
 *  ~15-line pure function with its own test file — the finding mapping and the session mapping
 *  fail for entirely different reasons. */
final class NewScans {

    private NewScans() {
    }

    static NewScan of(ScanRun run) {
        Objects.requireNonNull(run, "run");
        return NewScan.finished(
                run.target(), run.startedAt(), run.finishedAt(), statusOf(run.completion()));
    }

    /** The canonical {@code ScanCompletion -&gt; ScanStatus} mapping (§3.6). {@code RUNNING} is
     *  never produced. */
    static ScanStatus statusOf(ScanCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        return switch (completion) {
            case COMPLETED -> ScanStatus.COMPLETED;
            case COMPLETED_WITH_ERRORS -> ScanStatus.FAILED;
            case CANCELLED -> ScanStatus.CANCELLED;
        };
    }
}
