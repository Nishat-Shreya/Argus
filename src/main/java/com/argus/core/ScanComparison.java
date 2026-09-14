package com.argus.core;

import java.util.Objects;

/** What the view renders: both scans' identities plus the diff between them (plan §3.1). */
public record ScanComparison(ScanSummary baseline, ScanSummary current, ScanDiffReport diff) {

    public ScanComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(diff, "diff must not be null");
    }
}
