package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingDelta;
import com.argus.core.FindingSnapshot;
import com.argus.core.ScanDiffReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 7.6: {@code DiffRows} — toolkit-free mapping from {@code ScanDiffReport} onto
 *  {@code DiffRow} (plan §3.5, §4.4). */
class DiffRowsTest {

    @Test
    void anAddedPortFindingRendersEmptyBaselineAndCurrentOpen() {
        FindingSnapshot added = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        ScanDiffReport report = new ScanDiffReport(List.of(added), List.of(), List.of());

        List<DiffRow> rows = DiffRows.of(report);

        assertEquals(1, rows.size());
        assertEquals("added", rows.get(0).change());
        assertEquals("", rows.get(0).baseline());
        assertEquals("open", rows.get(0).current());
    }

    @Test
    void aRemovedPortFindingRendersBaselineOpenAndEmptyCurrent() {
        FindingSnapshot removed = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        ScanDiffReport report = new ScanDiffReport(List.of(), List.of(removed), List.of());

        List<DiffRow> rows = DiffRows.of(report);

        assertEquals(1, rows.size());
        assertEquals("removed", rows.get(0).change());
        assertEquals("open", rows.get(0).baseline());
        assertEquals("", rows.get(0).current());
    }

    @Test
    void aChangedPortFindingRendersBothCellsAndDiffChangedStyle() {
        FindingDelta delta = new FindingDelta(
                new FindingSnapshot(1L, "PORT", "host", 80, "OPEN"),
                new FindingSnapshot(2L, "PORT", "host", 80, "FILTERED"));
        ScanDiffReport report = new ScanDiffReport(List.of(), List.of(), List.of(delta));

        List<DiffRow> rows = DiffRows.of(report);

        assertEquals(1, rows.size());
        assertEquals("open", rows.get(0).baseline());
        assertEquals("filtered", rows.get(0).current());
        assertEquals("diff-changed", rows.get(0).styleClass());
    }

    @Test
    void anAddedSubdomainRendersEmptyPortAndPresentCurrent() {
        FindingSnapshot added = new FindingSnapshot(1L, "SUBDOMAIN", "a.example.com", null, null);
        ScanDiffReport report = new ScanDiffReport(List.of(added), List.of(), List.of());

        List<DiffRow> rows = DiffRows.of(report);

        assertEquals("", rows.get(0).port());
        assertEquals("present", rows.get(0).current());
        assertEquals("", rows.get(0).baseline());
    }

    @Test
    void rowOrderIsAddedThenRemovedThenChangedPreservingInputOrder() {
        FindingSnapshot addedA = new FindingSnapshot(1L, "PORT", "host", 1, "OPEN");
        FindingSnapshot addedB = new FindingSnapshot(2L, "PORT", "host", 2, "OPEN");
        FindingSnapshot removedA = new FindingSnapshot(3L, "PORT", "host", 3, "OPEN");
        FindingSnapshot removedB = new FindingSnapshot(4L, "PORT", "host", 4, "OPEN");
        FindingDelta changedA = new FindingDelta(
                new FindingSnapshot(5L, "PORT", "host", 5, "OPEN"),
                new FindingSnapshot(6L, "PORT", "host", 5, "FILTERED"));
        FindingDelta changedB = new FindingDelta(
                new FindingSnapshot(7L, "PORT", "host", 6, "OPEN"),
                new FindingSnapshot(8L, "PORT", "host", 6, "FILTERED"));

        ScanDiffReport report = new ScanDiffReport(List.of(addedA, addedB),
                List.of(removedA, removedB), List.of(changedA, changedB));

        List<DiffRow> rows = DiffRows.of(report);

        assertEquals(6, rows.size());
        assertEquals("1", rows.get(0).port());
        assertEquals("2", rows.get(1).port());
        assertEquals("3", rows.get(2).port());
        assertEquals("4", rows.get(3).port());
        assertEquals("5", rows.get(4).port());
        assertEquals("6", rows.get(5).port());
    }

    @Test
    void summaryLineOfAnEmptyReportSaysNoChanges() {
        ScanDiffReport report = new ScanDiffReport(List.of(), List.of(), List.of());
        assertTrue(DiffRows.summaryLine(report).toLowerCase().contains("no changes"));
    }

    @Test
    void summaryLineOfAPopulatedReportCountsEachCategory() {
        FindingSnapshot added = new FindingSnapshot(1L, "PORT", "host", 1, "OPEN");
        FindingSnapshot removedA = new FindingSnapshot(2L, "PORT", "host", 2, "OPEN");
        FindingSnapshot removedB = new FindingSnapshot(3L, "PORT", "host", 3, "OPEN");
        FindingDelta changed = new FindingDelta(
                new FindingSnapshot(4L, "PORT", "host", 4, "OPEN"),
                new FindingSnapshot(5L, "PORT", "host", 4, "FILTERED"));
        ScanDiffReport report = new ScanDiffReport(
                List.of(added), List.of(removedA, removedB), List.of(changed));

        String summary = DiffRows.summaryLine(report);

        assertTrue(summary.contains("1"));
        assertTrue(summary.contains("2"));
    }

    @Test
    void nullReportThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> DiffRows.of(null));
    }
}
