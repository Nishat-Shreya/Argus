package com.argus.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The fact that a scan completed successfully, sent by the email channel after EVERY such scan --
 * unlike {@link ScanAlert}, which exists only when a scan found something new (and is what the
 * webhook and desktop channels still use). Pure data, no toolkit types.
 *
 * @param baselineScanId the earlier completed scan of the same target this one was compared
 *                       with, or empty when there was none (a first scan) or the comparison was
 *                       unavailable
 * @param newFindings    the new-findings alert for this scan, or empty when nothing was new (or
 *                       there was no baseline to compare against)
 */
public record ScanCompletionNotice(String target, long scanId, int findingCount,
        OptionalLong baselineScanId, Optional<ScanAlert> newFindings) {

    public ScanCompletionNotice {
        Objects.requireNonNull(target, "target must not be null");
        if (scanId < 0) {
            throw new IllegalArgumentException("scanId must be >= 0");
        }
        if (findingCount < 0) {
            throw new IllegalArgumentException("findingCount must be >= 0");
        }
        Objects.requireNonNull(baselineScanId, "baselineScanId must not be null");
        Objects.requireNonNull(newFindings, "newFindings must not be null");
        if (newFindings.isPresent() && baselineScanId.isEmpty()) {
            throw new IllegalArgumentException("new findings require a baseline scan");
        }
    }

    /** How many findings this scan added over its baseline; 0 when none or no baseline. */
    public int newFindingCount() {
        return newFindings.map(ScanAlert::addedCount).orElse(0);
    }
}
