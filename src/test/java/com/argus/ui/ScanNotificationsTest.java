package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingDelta;
import com.argus.core.FindingSnapshot;
import com.argus.core.ScanAlert;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanCompletionNotice;
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

    // ---- alertFor() / desktopNotification() (P3-03 §3.3) -----------------

    @Test
    void n18EmptyAddedYieldsNoAlertEvenWithRemovedAndChanged() {
        ScanDiffReport report = new ScanDiffReport(List.of(),
                List.of(finding("gone.example.com", null, null)),
                List.of(new FindingDelta(finding("host", 80, "OPEN"),
                        finding("host", 80, "FILTERED"))));
        ScanComparison comparison = comparison(report);
        assertEquals(Optional.empty(), ScanNotifications.alertFor(comparison));
    }

    @Test
    void n19HappyPathYieldsTargetIdsCountAndSubjectsInOrder() {
        ScanDiffReport report = new ScanDiffReport(List.of(
                finding("a.example.com", null, null),
                finding("host", 8080, "OPEN")), List.of(), List.of());
        ScanComparison comparison = comparison(report);
        Optional<ScanAlert> alert = ScanNotifications.alertFor(comparison);
        assertTrue(alert.isPresent());
        assertEquals("example.com", alert.get().target());
        assertEquals(1L, alert.get().baselineScanId());
        assertEquals(2L, alert.get().currentScanId());
        assertEquals(2, alert.get().addedCount());
        assertEquals(List.of("a.example.com", "host:8080"), alert.get().addedSubjects());
    }

    @Test
    void n20SixtyAddedFindingsCapsSubjectsAtFiftyAndMarksTruncated() {
        List<FindingSnapshot> added = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            added.add(finding("host" + i + ".example.com", null, null));
        }
        ScanDiffReport report = new ScanDiffReport(added, List.of(), List.of());
        ScanComparison comparison = comparison(report);
        Optional<ScanAlert> alert = ScanNotifications.alertFor(comparison);
        assertTrue(alert.isPresent());
        assertEquals(60, alert.get().addedCount());
        assertEquals(50, alert.get().addedSubjects().size());
        assertTrue(alert.get().subjectsTruncated());
    }

    @Test
    void n21DesktopNotificationAgreesWithForComparisonStrings() {
        ScanDiffReport oneAdd =
                new ScanDiffReport(List.of(finding("api.example.com", null, null)), List.of(),
                        List.of());
        ScanDiffReport threeAdd = new ScanDiffReport(List.of(
                finding("a.example.com", null, null),
                finding("b.example.com", null, null),
                finding("c.example.com", null, null)), List.of(), List.of());
        ScanDiffReport sixAdd = new ScanDiffReport(List.of(
                finding("a.example.com", null, null),
                finding("b.example.com", null, null),
                finding("c.example.com", null, null),
                finding("d.example.com", null, null),
                finding("e.example.com", null, null),
                finding("f.example.com", null, null)), List.of(), List.of());

        for (ScanDiffReport report : List.of(oneAdd, threeAdd, sixAdd)) {
            ScanComparison comparison = comparison(report);
            DesktopNotification viaForComparison =
                    ScanNotifications.forComparison(comparison).orElseThrow();
            DesktopNotification viaAlert = ScanNotifications
                    .desktopNotification(ScanNotifications.alertFor(comparison).orElseThrow());
            assertEquals(viaForComparison.caption(), viaAlert.caption());
            assertEquals(viaForComparison.text(), viaAlert.text());
        }
    }

    @Test
    void n22ForComparisonDelegatesToAlertForAndDesktopNotification() {
        List<ScanComparison> comparisons = List.of(
                comparison(new ScanDiffReport(List.of(), List.of(), List.of())),
                comparison(new ScanDiffReport(
                        List.of(finding("a.example.com", null, null)), List.of(), List.of())),
                comparison(new ScanDiffReport(List.of(
                        finding("a.example.com", null, null),
                        finding("b.example.com", null, null),
                        finding("c.example.com", null, null),
                        finding("d.example.com", null, null)), List.of(), List.of())));

        for (ScanComparison comparison : comparisons) {
            assertEquals(ScanNotifications.alertFor(comparison)
                            .map(ScanNotifications::desktopNotification),
                    ScanNotifications.forComparison(comparison));
        }
    }

    @Test
    void n23BlankTargetStillProducesAnAlertAndANotification() {
        ScanSummary blankTargetCurrent = new ScanSummary(2L, "   ", STARTED, FINISHED, "COMPLETED");
        ScanSummary baseline = summary(1L, "   ", "COMPLETED");
        ScanDiffReport report =
                new ScanDiffReport(List.of(finding("a.example.com", null, null)), List.of(),
                        List.of());
        ScanComparison comparison = new ScanComparison(baseline, blankTargetCurrent, report);

        Optional<ScanAlert> alert = ScanNotifications.alertFor(comparison);
        assertTrue(alert.isPresent());
        assertEquals("   ", alert.get().target());

        Optional<DesktopNotification> notification = ScanNotifications.forComparison(comparison);
        assertTrue(notification.isPresent());
    }

    @Test
    void n24Malformed() {
        assertThrows(NullPointerException.class, () -> ScanNotifications.alertFor(null));
        assertThrows(NullPointerException.class, () -> ScanNotifications.desktopNotification(null));
    }

    // ---- helpers -----------------------------------------------------

    private static ScanOutcome outcome(ScanCompletion completion, Long savedScanId) {
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, completion, List.of());
        return new ScanOutcome(run, 0, savedScanId);
    }

    // ---- completionNotice(): the every-successful-scan email --------------------------

    @Test
    void n28ACompletedSavedScanWithNoBaselineStillProducesANotice() {
        ScanCompletionNotice notice = ScanNotifications.completionNotice(
                outcome(ScanCompletion.COMPLETED, 7L), Optional.empty(), Optional.empty());

        assertEquals("example.com", notice.target());
        assertEquals(7L, notice.scanId());
        assertTrue(notice.baselineScanId().isEmpty());
        assertEquals(0, notice.newFindingCount());
    }

    @Test
    void n29ACompletedScanWithNothingNewStillProducesANoticeNamingTheBaseline() {
        ScanCompletionNotice notice = ScanNotifications.completionNotice(
                outcome(ScanCompletion.COMPLETED, 7L), Optional.of(4L), Optional.empty());

        assertEquals(4L, notice.baselineScanId().getAsLong());
        assertEquals(0, notice.newFindingCount());
    }

    @Test
    void n30NewFindingsAreCarriedOnTheNotice() {
        ScanAlert alert = new ScanAlert("example.com", 4L, 7L, 2, List.of("a", "b"));

        ScanCompletionNotice notice = ScanNotifications.completionNotice(
                outcome(ScanCompletion.COMPLETED, 7L), Optional.of(4L), Optional.of(alert));

        assertEquals(2, notice.newFindingCount());
    }

    @Test
    void n31OnlyCompletedSavedScansMayProduceANoticeSoFailedScansNeverEmail() {
        for (ScanCompletion completion : List.of(ScanCompletion.COMPLETED_WITH_ERRORS,
                ScanCompletion.CANCELLED)) {
            assertThrows(IllegalArgumentException.class, () -> ScanNotifications.completionNotice(
                    outcome(completion, 7L), Optional.empty(), Optional.empty()));
        }
        assertThrows(IllegalArgumentException.class, () -> ScanNotifications.completionNotice(
                outcome(ScanCompletion.COMPLETED, null), Optional.empty(), Optional.empty()));
    }

    // ---- noAlertReason(): why no alert (and so no email) was produced -----

    @Test
    void n25NoBaselineIsReportedAsFirstScanOfTheTarget() {
        String reason = ScanNotifications.noAlertReason("kuet.ac.bd", Optional.empty());

        assertTrue(reason.contains("kuet.ac.bd"));
        assertTrue(reason.contains("no earlier completed scan"));
    }

    @Test
    void n26BaselineWithNothingAddedNamesTheBaselineScan() {
        String reason = ScanNotifications.noAlertReason("kuet.ac.bd", Optional.of(54L));

        assertTrue(reason.contains("no new findings"));
        assertTrue(reason.contains("#54"));
    }

    @Test
    void n27NoAlertReasonRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> ScanNotifications.noAlertReason(null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> ScanNotifications.noAlertReason("kuet.ac.bd", null));
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
