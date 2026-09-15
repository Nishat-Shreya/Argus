package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingDelta;
import com.argus.core.FindingSnapshot;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanComparison;
import com.argus.core.ScanDiffReport;
import com.argus.core.ScanRun;
import com.argus.core.ScanSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Section 6.2: {@code ScanNotifications} — the core of P3-02. Pure, toolkit-free, AWT-free.
 */
class ScanNotificationsTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = STARTED.plusSeconds(120);

    // ---- eligible() ----------------------------------------------------

    @Test
    void n1SavedAndCompletedIsEligible() {
        assertTrue(ScanNotifications.eligible(outcome(ScanCompletion.COMPLETED, 3L)));
    }

    @Test
    void n2SavedAndCancelledIsNotEligible() {
        assertFalse(ScanNotifications.eligible(outcome(ScanCompletion.CANCELLED, 3L)));
    }

    @Test
    void n3SavedAndCompletedWithErrorsIsNotEligible() {
        assertFalse(
                ScanNotifications.eligible(outcome(ScanCompletion.COMPLETED_WITH_ERRORS, 3L)));
    }

    @Test
    void n4CompletedButNotSavedIsNotEligible() {
        assertFalse(ScanNotifications.eligible(outcome(ScanCompletion.COMPLETED, null)));
    }

    // ---- baselineScanId() ------------------------------------------------

    @Test
    void n5HappyPathPicksGreatestLesserId() {
        List<ScanSummary> summaries = List.of(
                summary(1L, "example.com", "COMPLETED"),
                summary(2L, "example.com", "COMPLETED"),
                summary(3L, "example.com", "COMPLETED"));
        Optional<Long> baseline = ScanNotifications.baselineScanId(summaries, "example.com", 3L);
        assertEquals(Optional.of(2L), baseline);
    }

    @Test
    void n6FirstEverScanHasNoBaseline() {
        List<ScanSummary> summaries = List.of(summary(1L, "example.com", "COMPLETED"));
        Optional<Long> baseline = ScanNotifications.baselineScanId(summaries, "example.com", 1L);
        assertEquals(Optional.empty(), baseline);
    }

    @Test
    void n7NonCompletedImmediatePreviousIsSkippedForOlderCompleted() {
        List<ScanSummary> summaries = List.of(
                summary(1L, "example.com", "COMPLETED"),
                summary(2L, "example.com", "CANCELLED"),
                summary(3L, "example.com", "COMPLETED"));
        Optional<Long> baseline = ScanNotifications.baselineScanId(summaries, "example.com", 3L);
        assertEquals(Optional.of(1L), baseline);
    }

    @Test
    void n8TargetFilterIsCaseSensitiveAndExact() {
        List<ScanSummary> summaries = List.of(
                summary(1L, "example.com", "COMPLETED"),
                summary(2L, "Example.com", "COMPLETED"),
                summary(3L, "other.com", "COMPLETED"),
                summary(4L, "example.com", "COMPLETED"));
        Optional<Long> baseline = ScanNotifications.baselineScanId(summaries, "example.com", 4L);
        assertEquals(Optional.of(1L), baseline);
    }

    @Test
    void n9OrderingIndependence() {
        List<ScanSummary> ordered = new ArrayList<>(List.of(
                summary(1L, "example.com", "COMPLETED"),
                summary(2L, "example.com", "COMPLETED"),
                summary(3L, "example.com", "COMPLETED")));
        List<ScanSummary> shuffled = new ArrayList<>(ordered);
        Collections.reverse(shuffled);

        Optional<Long> orderedBaseline =
                ScanNotifications.baselineScanId(ordered, "example.com", 3L);
        Optional<Long> shuffledBaseline =
                ScanNotifications.baselineScanId(shuffled, "example.com", 3L);
        assertEquals(orderedBaseline, shuffledBaseline);
        assertEquals(Optional.of(2L), orderedBaseline);
    }

    @Test
    void n10FutureIdIsNeverChosen() {
        List<ScanSummary> summaries = List.of(
                summary(1L, "example.com", "COMPLETED"),
                summary(5L, "example.com", "COMPLETED"));
        Optional<Long> baseline = ScanNotifications.baselineScanId(summaries, "example.com", 2L);
        assertEquals(Optional.of(1L), baseline);
    }

    @Test
    void n11MalformedInput() {
        assertEquals(Optional.empty(),
                ScanNotifications.baselineScanId(List.of(), "example.com", 1L));
        List<ScanSummary> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class,
                () -> ScanNotifications.baselineScanId(withNull, "example.com", 1L));
        assertThrows(NullPointerException.class,
                () -> ScanNotifications.baselineScanId(List.of(), null, 1L));
    }

    // ---- forComparison() -------------------------------------------------

    @Test
    void n12EmptyAddedYieldsNoNotificationEvenWithRemovedAndChanged() {
        ScanDiffReport report = new ScanDiffReport(List.of(),
                List.of(finding("gone.example.com", null, null)),
                List.of(new FindingDelta(finding("host", 80, "OPEN"),
                        finding("host", 80, "FILTERED"))));
        ScanComparison comparison = comparison(report);
        assertEquals(Optional.empty(), ScanNotifications.forComparison(comparison));
    }

    @Test
    void n13OneAdditionIsSingular() {
        ScanDiffReport report =
                new ScanDiffReport(List.of(finding("api.example.com", null, null)), List.of(),
                        List.of());
        ScanComparison comparison = comparison(report);
        Optional<DesktopNotification> notification = ScanNotifications.forComparison(comparison);
        assertTrue(notification.isPresent());
        assertEquals("Argus · 1 new finding", notification.get().caption());
        assertEquals("example.com · api.example.com", notification.get().text());
    }

    @Test
    void n14ThreeAdditionsListedWithNoMoreClause() {
        ScanDiffReport report = new ScanDiffReport(List.of(
                finding("a.example.com", null, null),
                finding("b.example.com", null, null),
                finding("c.example.com", null, null)), List.of(), List.of());
        ScanComparison comparison = comparison(report);
        Optional<DesktopNotification> notification = ScanNotifications.forComparison(comparison);
        assertTrue(notification.isPresent());
        assertEquals("Argus · 3 new findings", notification.get().caption());
        assertEquals("example.com · a.example.com, b.example.com, c.example.com",
                notification.get().text());
    }

    @Test
    void n15SixAdditionsListsFirstThreeThenAndKMore() {
        ScanDiffReport report = new ScanDiffReport(List.of(
                finding("a.example.com", null, null),
                finding("b.example.com", null, null),
                finding("c.example.com", null, null),
                finding("d.example.com", null, null),
                finding("e.example.com", null, null),
                finding("f.example.com", null, null)), List.of(), List.of());
        ScanComparison comparison = comparison(report);
        Optional<DesktopNotification> notification = ScanNotifications.forComparison(comparison);
        assertTrue(notification.isPresent());
        assertEquals("Argus · 6 new findings", notification.get().caption());
        assertEquals(
                "example.com · a.example.com, b.example.com, c.example.com and 3 more",
                notification.get().text());
    }

    @Test
    void n16Describe() {
        assertEquals("host:8080", ScanNotifications.describe(finding("host", 8080, "OPEN")));
        assertEquals("api.example.com",
                ScanNotifications.describe(finding("api.example.com", null, null)));
        assertEquals("host", ScanNotifications.describe(finding("host", 443, null)));
        assertEquals("host",
                ScanNotifications.describe(new FindingSnapshot(1L, "CERTIFICATE", "host", null,
                        null)));
    }

    @Test
    void n17LongSubjectsStillProduceAConstructibleNotification() {
        String longSubject = "s".repeat(300) + ".example.com";
        ScanDiffReport report = new ScanDiffReport(List.of(
                finding(longSubject, null, null),
                finding(longSubject, null, null),
                finding(longSubject, null, null)), List.of(), List.of());
        ScanComparison comparison = comparison(report);
        Optional<DesktopNotification> notification = ScanNotifications.forComparison(comparison);
        assertTrue(notification.isPresent());
        assertTrue(notification.get().text().length() <= DesktopNotification.MAX_TEXT_CHARS);
    }

    // ---- helpers -----------------------------------------------------

    private static ScanOutcome outcome(ScanCompletion completion, Long savedScanId) {
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, completion, List.of());
        return new ScanOutcome(run, 0, savedScanId);
    }

    private static ScanSummary summary(long id, String target, String status) {
        return new ScanSummary(id, target, STARTED, FINISHED, status);
    }

    private static FindingSnapshot finding(String subject, Integer port, String state) {
        return new FindingSnapshot(1L, "PORT", subject, port, state);
    }

    private static ScanComparison comparison(ScanDiffReport report) {
        ScanSummary baseline = summary(1L, "example.com", "COMPLETED");
        ScanSummary current = summary(2L, "example.com", "COMPLETED");
        return new ScanComparison(baseline, current, report);
    }
}
