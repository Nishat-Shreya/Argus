package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanSummary;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 7.1: {@code Timelines} — toolkit-free axis rules (plan §3.3). Fixed {@code ZoneId}
 *  only — never {@code ZoneId.systemDefault()}, never a clock. */
class TimelinesTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void targetsListsOnlyTargetsWithACompletedScanAlphabetically() {
        ScanSummary a = summary(1L, "zzz.com", STARTED, "COMPLETED");
        ScanSummary b = summary(2L, "aaa.com", STARTED, "COMPLETED");

        List<String> targets = Timelines.targets(List.of(a, b));

        assertEquals(List.of("aaa.com", "zzz.com"), targets);
    }

    @Test
    void targetsExcludesATargetWhoseOnlyScansAreCancelledOrFailedOrRunning() {
        ScanSummary cancelled = summary(1L, "cancelled.com", STARTED, "CANCELLED");
        ScanSummary failed = summary(2L, "failed.com", STARTED, "FAILED");
        ScanSummary running = summary(3L, "running.com", STARTED, "RUNNING");
        ScanSummary complete = summary(4L, "complete.com", STARTED, "COMPLETED");

        List<String> targets = Timelines.targets(List.of(cancelled, failed, running, complete));

        assertEquals(List.of("complete.com"), targets);
    }

    @Test
    void trackOrdersOldestFirstEvenWhenTheInputIsNewestFirst() {
        ScanSummary newest = summary(3L, "example.com", STARTED.plusSeconds(120), "COMPLETED");
        ScanSummary middle = summary(2L, "example.com", STARTED.plusSeconds(60), "COMPLETED");
        ScanSummary oldest = summary(1L, "example.com", STARTED, "COMPLETED");

        TimelineTrack track = Timelines.track(List.of(newest, middle, oldest), "example.com", ZONE);

        assertEquals(1L, track.scanIdAt(0));
        assertEquals(2L, track.scanIdAt(1));
        assertEquals(3L, track.scanIdAt(2));
    }

    @Test
    void trackBreaksAStartedAtTieByAscendingId() {
        ScanSummary higherId = summary(2L, "example.com", STARTED, "COMPLETED");
        ScanSummary lowerId = summary(1L, "example.com", STARTED, "COMPLETED");

        TimelineTrack track = Timelines.track(List.of(higherId, lowerId), "example.com", ZONE);

        assertEquals(1L, track.scanIdAt(0));
        assertEquals(2L, track.scanIdAt(1));
    }

    @Test
    void trackExcludesOtherTargetsAndNonCompleteScans() {
        ScanSummary other = summary(1L, "other.com", STARTED, "COMPLETED");
        ScanSummary cancelled = summary(2L, "example.com", STARTED, "CANCELLED");
        ScanSummary keep = summary(3L, "example.com", STARTED, "COMPLETED");

        TimelineTrack track = Timelines.track(List.of(other, cancelled, keep), "example.com", ZONE);

        assertEquals(1, track.size());
        assertEquals(3L, track.scanIdAt(0));
    }

    @Test
    void trackKeepsTheMostRecentMaxPointsAndReportsTheFullTotal() {
        List<ScanSummary> scans = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            scans.add(summary(i + 1, "example.com", STARTED.plusSeconds(i * 60L), "COMPLETED"));
        }

        TimelineTrack track = Timelines.track(scans, "example.com", ZONE);

        assertEquals(30, track.size());
        assertEquals(31, track.totalForTarget());
        assertTrue(track.truncated());
        // the oldest (id 1) was dropped, not the newest (id 31)
        assertEquals(2L, track.scanIdAt(0));
        assertEquals(31L, track.scanIdAt(29));
    }

    @Test
    void trackOfAnUnknownTargetIsEmptyAndNotAnError() {
        ScanSummary scan = summary(1L, "example.com", STARTED, "COMPLETED");

        TimelineTrack track = Timelines.track(List.of(scan), "unknown.com", ZONE);

        assertTrue(track.isEmpty());
        assertEquals(0, track.totalForTarget());
        assertFalse(track.truncated());
    }

    @Test
    void labelCarriesDateAndTimeInTheSuppliedZone() {
        ScanSummary scan = summary(1L, "example.com", STARTED, "COMPLETED");

        TimelineTrack utcTrack = Timelines.track(List.of(scan), "example.com", ZoneOffset.UTC);
        TimelineTrack offsetTrack =
                Timelines.track(List.of(scan), "example.com", ZoneOffset.ofHoursMinutes(5, 30));

        assertFalse(utcTrack.point(0).label().equals(offsetTrack.point(0).label()));
    }

    @Test
    void indexForRoundsHalfUpAndClamps() {
        assertEquals(1, Timelines.indexFor(1.4, 10));
        assertEquals(2, Timelines.indexFor(1.5, 10));
        assertEquals(3, Timelines.indexFor(2.5, 10));
        assertEquals(0, Timelines.indexFor(-0.6, 10));
        assertEquals(9, Timelines.indexFor(99, 10));
    }

    @Test
    void indexForOnAnEmptyTrackIsMinusOne() {
        assertEquals(-1, Timelines.indexFor(0, 0));
    }

    @Test
    void sliderMaxIsZeroForASinglePointTrack() {
        ScanSummary scan = summary(1L, "example.com", STARTED, "COMPLETED");
        TimelineTrack track = Timelines.track(List.of(scan), "example.com", ZONE);

        assertEquals(0.0, track.sliderMax());
    }

    @Test
    void positionLabelReadsScanKOfN() {
        ScanSummary a = summary(1L, "example.com", STARTED, "COMPLETED");
        ScanSummary b = summary(2L, "example.com", STARTED.plusSeconds(60), "COMPLETED");
        ScanSummary c = summary(3L, "example.com", STARTED.plusSeconds(120), "COMPLETED");
        TimelineTrack track = Timelines.track(List.of(a, b, c), "example.com", ZONE);

        String label = Timelines.positionLabel(track, 1);

        assertTrue(label.startsWith("scan 2 of 3"));
    }

    @Test
    void positionLabelIsBlankForAnEmptyTrackOrOutOfRangeIndex() {
        ScanSummary a = summary(1L, "example.com", STARTED, "COMPLETED");
        TimelineTrack track = Timelines.track(List.of(a), "example.com", ZONE);
        TimelineTrack empty = Timelines.track(List.of(a), "unknown.com", ZONE);

        assertEquals("", Timelines.positionLabel(empty, 0));
        assertEquals("", Timelines.positionLabel(track, -1));
        assertEquals("", Timelines.positionLabel(track, 1));
    }

    @Test
    void truncationNoteIsBlankWhenNotTruncatedAndNamesBothCountsAndTheTargetWhenItIs() {
        ScanSummary a = summary(1L, "example.com", STARTED, "COMPLETED");
        TimelineTrack notTruncated = Timelines.track(List.of(a), "example.com", ZONE);
        assertEquals("", Timelines.truncationNote(notTruncated));

        List<ScanSummary> scans = new ArrayList<>();
        for (int i = 0; i < 57; i++) {
            scans.add(summary(i + 1, "example.com", STARTED.plusSeconds(i * 60L), "COMPLETED"));
        }
        TimelineTrack truncated = Timelines.track(scans, "example.com", ZONE);

        String note = Timelines.truncationNote(truncated);
        assertTrue(note.contains("30"));
        assertTrue(note.contains("57"));
        assertTrue(note.contains("example.com"));
    }

    @Test
    void hiddenNoteIsBlankAtZeroAndCountsScansOtherwise() {
        assertEquals("", Timelines.hiddenNote(0));

        String note = Timelines.hiddenNote(3);
        assertTrue(note.contains("3"));
    }

    @Test
    void everyFactoryRejectsNull() {
        assertThrows(NullPointerException.class, () -> Timelines.targets(null));
        assertThrows(NullPointerException.class, () -> Timelines.track(null, "example.com", ZONE));
        assertThrows(NullPointerException.class, () -> Timelines.track(List.of(), null, ZONE));
        assertThrows(NullPointerException.class, () -> Timelines.track(List.of(), "example.com", null));
        assertThrows(NullPointerException.class, () -> Timelines.positionLabel(null, 0));
        assertThrows(NullPointerException.class, () -> Timelines.truncationNote(null));
    }

    @Test
    void theTrackRecordRejectsATotalSmallerThanItsPointCount() {
        TimelinePoint point = new TimelinePoint(1L, "example.com", "2026-01-01 00:00:00");
        assertThrows(IllegalArgumentException.class,
                () -> new TimelineTrack("example.com", List.of(point), 0));
    }

    private static ScanSummary summary(long id, String target, Instant startedAt, String status) {
        return new ScanSummary(id, target, startedAt, startedAt.plusSeconds(300), status);
    }
}
