package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ScanComparison} — what the view renders (plan §3.1). */
class ScanComparisonTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void preservesBaselineCurrentAndDiff() {
        ScanSummary baseline = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        ScanSummary current = new ScanSummary(2L, "example.com", STARTED, FINISHED, "COMPLETED");
        ScanDiffReport diff = new ScanDiffReport(List.of(), List.of(), List.of());

        ScanComparison comparison = new ScanComparison(baseline, current, diff);

        assertEquals(baseline, comparison.baseline());
        assertEquals(current, comparison.current());
        assertEquals(diff, comparison.diff());
    }

    @Test
    void aNullBaselineThrows() {
        ScanSummary current = new ScanSummary(2L, "example.com", STARTED, FINISHED, "COMPLETED");
        ScanDiffReport diff = new ScanDiffReport(List.of(), List.of(), List.of());
        assertThrows(NullPointerException.class, () -> new ScanComparison(null, current, diff));
    }

    @Test
    void aNullCurrentThrows() {
        ScanSummary baseline = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        ScanDiffReport diff = new ScanDiffReport(List.of(), List.of(), List.of());
        assertThrows(NullPointerException.class, () -> new ScanComparison(baseline, null, diff));
    }

    @Test
    void aNullDiffThrows() {
        ScanSummary baseline = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        ScanSummary current = new ScanSummary(2L, "example.com", STARTED, FINISHED, "COMPLETED");
        assertThrows(NullPointerException.class, () -> new ScanComparison(baseline, current, null));
    }
}
