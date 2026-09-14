package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ScanDiffReports} — the package-private {@code ScanDiff -&gt; ScanDiffReport} mapper
 *  (plan §3.2, §7.3). */
class ScanDiffReportsTest {

    @Test
    void findingRecordToFindingSnapshotPreservesIdAsAPlainLongAndTypeAsName() {
        FindingRecord record = new FindingRecord(5L, 10L, FindingType.PORT, "host", 80, "OPEN");

        FindingSnapshot snapshot = ScanDiffReports.of(record);

        assertEquals(5L, snapshot.id());
        assertEquals("PORT", snapshot.type());
        assertEquals("host", snapshot.subject());
        assertEquals(80, snapshot.port());
        assertEquals("OPEN", snapshot.state());
    }

    @Test
    void aSubdomainRecordProjectsWithBothNullsIntact() {
        FindingRecord record =
                new FindingRecord(6L, 10L, FindingType.SUBDOMAIN, "a.example.com", null, null);

        FindingSnapshot snapshot = ScanDiffReports.of(record);

        assertEquals("SUBDOMAIN", snapshot.type());
        assertNull(snapshot.port());
        assertNull(snapshot.state());
    }

    @Test
    void findingChangeToFindingDeltaPreservesBothSidesAndBothIds() {
        FindingRecord baselineRecord = new FindingRecord(1L, 10L, FindingType.PORT, "host", 80,
                "OPEN");
        FindingRecord currentRecord = new FindingRecord(2L, 20L, FindingType.PORT, "host", 80,
                "FILTERED");
        FindingChange change = new FindingChange(baselineRecord, currentRecord);

        FindingDelta delta = ScanDiffReports.of(change);

        assertEquals(1L, delta.baseline().id());
        assertEquals("OPEN", delta.baseline().state());
        assertEquals(2L, delta.current().id());
        assertEquals("FILTERED", delta.current().state());
    }

    @Test
    void scanDiffToScanDiffReportPreservesSizesAndOrder() {
        FindingRecord addedFirst = new FindingRecord(101L, 20L, FindingType.PORT, "host", 443,
                "OPEN");
        FindingRecord addedSecond = new FindingRecord(102L, 20L, FindingType.PORT, "host", 8080,
                "OPEN");
        FindingRecord removedFirst = new FindingRecord(1L, 10L, FindingType.PORT, "host", 21,
                "OPEN");
        FindingRecord removedSecond = new FindingRecord(2L, 10L, FindingType.PORT, "host", 22,
                "OPEN");
        FindingChange change = new FindingChange(
                new FindingRecord(3L, 10L, FindingType.PORT, "host", 80, "OPEN"),
                new FindingRecord(103L, 20L, FindingType.PORT, "host", 80, "FILTERED"));

        ScanDiff diff = new ScanDiff(List.of(addedFirst, addedSecond),
                List.of(removedFirst, removedSecond), List.of(change));

        ScanDiffReport report = ScanDiffReports.of(diff);

        assertEquals(2, report.added().size());
        assertEquals(101L, report.added().get(0).id());
        assertEquals(102L, report.added().get(1).id());
        assertEquals(2, report.removed().size());
        assertEquals(1L, report.removed().get(0).id());
        assertEquals(2L, report.removed().get(1).id());
        assertEquals(1, report.changed().size());
    }

    @Test
    void anEmptyScanDiffProjectsToAnEmptyReport() {
        ScanDiff diff = new ScanDiff(List.of(), List.of(), List.of());

        ScanDiffReport report = ScanDiffReports.of(diff);

        assertTrue(report.isEmpty());
    }

    @Test
    void reportListsAreUnmodifiable() {
        FindingRecord added = new FindingRecord(1L, 20L, FindingType.PORT, "host", 80, "OPEN");
        ScanDiff diff = new ScanDiff(List.of(added), List.of(), List.of());

        ScanDiffReport report = ScanDiffReports.of(diff);

        assertThrows(UnsupportedOperationException.class,
                () -> report.added().add(ScanDiffReports.of(added)));
    }
}
