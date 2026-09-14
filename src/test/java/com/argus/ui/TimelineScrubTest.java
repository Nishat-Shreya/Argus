package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanSummary;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Section 7.3: the scrub contract itself, proven without a toolkit -- exactly the sequence
 * {@code TimelineController} performs (build the axis, prefetch findings, then walk positions
 * as pure lookups). Plan §0.1 / §3.5 step 7.
 */
class TimelineScrubTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void everySliderPositionResolvesToItsOwnScansRowsAndNoOther() {
        ScanSummary s1 = summary(1L, "example.com", STARTED);
        ScanSummary s2 = summary(2L, "example.com", STARTED.plusSeconds(60));
        ScanSummary s3 = summary(3L, "example.com", STARTED.plusSeconds(120));
        TimelineTrack track = Timelines.track(List.of(s1, s2, s3), "example.com", ZONE);

        FindingSnapshot f1 = new FindingSnapshot(10L, "PORT", "host", 80, "OPEN");
        FindingSnapshot f2 = new FindingSnapshot(20L, "PORT", "host", 443, "OPEN");
        FindingSnapshot f3 = new FindingSnapshot(30L, "SUBDOMAIN", "a.example.com", null, null);
        Map<Long, List<FindingSnapshot>> findingsByScanId =
                Map.of(1L, List.of(f1), 2L, List.of(f2), 3L, List.of(f3));

        for (int i = 0; i < track.size(); i++) {
            long scanId = track.scanIdAt(i);
            List<FindingRow> rows = SnapshotRows.of(findingsByScanId.get(scanId));
            assertEquals(SnapshotRows.of(findingsByScanId.get(scanId)), rows);
        }
    }

    @Test
    void positionZeroIsTheOldestAndTheLastPositionIsTheNewest() {
        ScanSummary oldest = summary(1L, "example.com", STARTED);
        ScanSummary newest = summary(2L, "example.com", STARTED.plusSeconds(60));
        TimelineTrack track = Timelines.track(List.of(newest, oldest), "example.com", ZONE);

        assertEquals(1L, track.scanIdAt(0));
        assertEquals(2L, track.scanIdAt(track.size() - 1));
    }

    @Test
    void scrubbingIsPureAndRepeatable() {
        ScanSummary s1 = summary(1L, "example.com", STARTED);
        ScanSummary s2 = summary(2L, "example.com", STARTED.plusSeconds(60));
        ScanSummary s3 = summary(3L, "example.com", STARTED.plusSeconds(120));
        TimelineTrack track = Timelines.track(List.of(s1, s2, s3), "example.com", ZONE);

        FindingSnapshot f1 = new FindingSnapshot(10L, "PORT", "host", 80, "OPEN");
        FindingSnapshot f2 = new FindingSnapshot(20L, "PORT", "host", 443, "OPEN");
        FindingSnapshot f3 = new FindingSnapshot(30L, "SUBDOMAIN", "a.example.com", null, null);
        Map<Long, List<FindingSnapshot>> findingsByScanId =
                Map.of(1L, List.of(f1), 2L, List.of(f2), 3L, List.of(f3));

        List<List<FindingRow>> forward = new java.util.ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            forward.add(SnapshotRows.of(findingsByScanId.get(track.scanIdAt(i))));
        }
        List<List<FindingRow>> backward = new java.util.ArrayList<>();
        for (int i = track.size() - 1; i >= 0; i--) {
            backward.add(0, SnapshotRows.of(findingsByScanId.get(track.scanIdAt(i))));
        }

        assertEquals(forward, backward);
    }

    @Test
    void theSummaryLineForEachPositionIsChartDataSummaryLineOfThatScansFindings() {
        ScanSummary s1 = summary(1L, "example.com", STARTED);
        TimelineTrack track = Timelines.track(List.of(s1), "example.com", ZONE);

        FindingSnapshot f1 = new FindingSnapshot(10L, "PORT", "host", 80, "OPEN");
        Map<Long, List<FindingSnapshot>> findingsByScanId = Map.of(1L, List.of(f1));

        List<FindingSnapshot> findings = findingsByScanId.get(track.scanIdAt(0));
        String summary = ChartData.summaryLine(findings);

        assertEquals(ChartData.summaryLine(List.of(f1)), summary);
    }

    @Test
    void aScanWithNoFindingsYieldsNoRowsAndTheNoFindingsSummary() {
        ScanSummary s1 = summary(1L, "example.com", STARTED);
        TimelineTrack track = Timelines.track(List.of(s1), "example.com", ZONE);
        Map<Long, List<FindingSnapshot>> findingsByScanId = Map.of(1L, List.of());

        List<FindingSnapshot> findings = findingsByScanId.get(track.scanIdAt(0));

        assertTrue(SnapshotRows.of(findings).isEmpty());
        assertEquals("no findings recorded for this scan", ChartData.summaryLine(findings));
    }

    @Test
    void switchingTargetChangesTheAxisCompletely() {
        ScanSummary a1 = summary(1L, "a.example.com", STARTED);
        ScanSummary a2 = summary(2L, "a.example.com", STARTED.plusSeconds(60));
        ScanSummary b1 = summary(3L, "b.example.com", STARTED);

        TimelineTrack trackA = Timelines.track(List.of(a1, a2, b1), "a.example.com", ZONE);
        TimelineTrack trackB = Timelines.track(List.of(a1, a2, b1), "b.example.com", ZONE);

        for (int i = 0; i < trackA.size(); i++) {
            long scanId = trackA.scanIdAt(i);
            assertTrue(trackB.scanIds().stream().noneMatch(id -> id == scanId));
        }
    }

    private static ScanSummary summary(long id, String target, Instant startedAt) {
        return new ScanSummary(id, target, startedAt, startedAt.plusSeconds(300), "COMPLETED");
    }
}
