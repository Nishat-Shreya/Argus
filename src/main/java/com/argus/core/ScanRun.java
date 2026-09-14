package com.argus.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One finished scan run, as the caller observed it: the input to ScanArchive.save.
 *  Scan-level time only — the no-per-finding-timestamp rule (P1-01/P1-02/P1-04) is unchanged. */
public record ScanRun(String target, Instant startedAt, Instant finishedAt,
        ScanCompletion completion, List<Object> findings) {

    public ScanRun {
        Objects.requireNonNull(target, "target must not be null");
        if (target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(completion, "completion must not be null");
        Objects.requireNonNull(findings, "findings must not be null");
        for (Object finding : findings) {
            Objects.requireNonNull(finding, "findings must not contain a null element");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "finishedAt must not precede startedAt: startedAt=" + startedAt
                            + ", finishedAt=" + finishedAt);
        }
        findings = List.copyOf(findings);
    }
}
