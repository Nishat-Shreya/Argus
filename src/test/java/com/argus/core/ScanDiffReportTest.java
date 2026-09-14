package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ScanDiffReport} — the projection of {@code ScanDiff} (plan §3.1). */
class ScanDiffReportTest {

    private static final FindingSnapshot ADDED = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
    private static final FindingSnapshot REMOVED_SNAP =
            new FindingSnapshot(2L, "PORT", "host", 22, "OPEN");
    private static final FindingDelta CHANGE = new FindingDelta(
            new FindingSnapshot(3L, "PORT", "host", 443, "OPEN"),
            new FindingSnapshot(4L, "PORT", "host", 443, "FILTERED"));

    @Test
    void isEmptyIsTrueWhenAllThreeListsAreEmpty() {
        ScanDiffReport report = new ScanDiffReport(List.of(), List.of(), List.of());
        assertTrue(report.isEmpty());
    }

    @Test
    void isEmptyIsFalseWhenAnyListIsNonEmpty() {
        assertFalse(new ScanDiffReport(List.of(ADDED), List.of(), List.of()).isEmpty());
        assertFalse(new ScanDiffReport(List.of(), List.of(REMOVED_SNAP), List.of()).isEmpty());
        assertFalse(new ScanDiffReport(List.of(), List.of(), List.of(CHANGE)).isEmpty());
    }

    @Test
    void listsAreUnmodifiable() {
        ScanDiffReport report =
                new ScanDiffReport(List.of(ADDED), List.of(REMOVED_SNAP), List.of(CHANGE));
        assertThrows(UnsupportedOperationException.class, () -> report.added().add(ADDED));
        assertThrows(
                UnsupportedOperationException.class, () -> report.removed().add(REMOVED_SNAP));
        assertThrows(UnsupportedOperationException.class, () -> report.changed().add(CHANGE));
    }

    @Test
    void preservesSizes() {
        ScanDiffReport report =
                new ScanDiffReport(List.of(ADDED), List.of(REMOVED_SNAP), List.of(CHANGE));
        assertEquals(1, report.added().size());
        assertEquals(1, report.removed().size());
        assertEquals(1, report.changed().size());
    }
}
