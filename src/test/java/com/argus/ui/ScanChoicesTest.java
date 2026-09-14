package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 7.7: {@code ScanChoices} — toolkit-free grouping/selection logic for the two
 *  {@code DatePicker}/{@code ComboBox} pairs (plan §3.5, §4.1, §4.4). Fixed {@code ZoneId} only —
 *  no {@code ZoneId.systemDefault()}, no clock read. */
class ScanChoicesTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void selectableKeepsOnlyCompleteSummariesAndPreservesNewestFirstOrder() {
        ScanSummary newest = summary(3L, "example.com", STARTED.plusSeconds(120), "COMPLETED");
        ScanSummary cancelled = summary(2L, "example.com", STARTED.plusSeconds(60), "CANCELLED");
        ScanSummary oldest = summary(1L, "example.com", STARTED, "COMPLETED");

        List<ScanChoice> choices =
                ScanChoices.selectable(List.of(newest, cancelled, oldest), ZONE);

        assertEquals(2, choices.size());
        assertEquals(3L, choices.get(0).scanId());
        assertEquals(1L, choices.get(1).scanId());
    }

    @Test
    void twoScansOnTheSameLocalDateBothAppearAndOnDateReturnsBothNewestFirst() {
        ScanSummary later = summary(2L, "example.com", STARTED.plusSeconds(3600), "COMPLETED");
        ScanSummary earlier = summary(1L, "example.com", STARTED, "COMPLETED");

        List<ScanChoice> choices = ScanChoices.selectable(List.of(later, earlier), ZONE);
        LocalDate date = choices.get(0).date();
        List<ScanChoice> onDate = ScanChoices.onDate(choices, date);

        assertEquals(2, onDate.size());
        assertEquals(2L, onDate.get(0).scanId());
        assertEquals(1L, onDate.get(1).scanId());
    }

    @Test
    void scansOnDifferentDatesInTheGivenZoneAreGroupedByThatZoneNotUtc() {
        // 2026-01-01T02:30:00Z is 2025-12-31T21:30 in America/New_York (UTC-5) -- a different
        // calendar date in the zone than in UTC.
        Instant nearUtcMidnight = Instant.parse("2026-01-01T02:30:00Z");
        ScanSummary scan = summary(1L, "example.com", nearUtcMidnight, "COMPLETED");

        List<ScanChoice> choices = ScanChoices.selectable(List.of(scan), ZONE);

        assertEquals(LocalDate.of(2025, 12, 31), choices.get(0).date());
    }

    @Test
    void datesIsDeduplicatedAndNewestFirst() {
        ScanSummary a = summary(1L, "example.com", STARTED, "COMPLETED");
        ScanSummary b = summary(2L, "example.com", STARTED.plusSeconds(60), "COMPLETED");
        ScanSummary c = summary(3L, "example.com", STARTED.plusSeconds(86_400), "COMPLETED");

        List<ScanChoice> choices = ScanChoices.selectable(List.of(c, b, a), ZONE);
        List<LocalDate> dates = ScanChoices.dates(choices);

        assertEquals(2, dates.size());
        assertTrue(dates.get(0).isAfter(dates.get(1)));
    }

    @Test
    void hiddenCountAndHiddenNoteCountOnlyNonCompleteScans() {
        ScanSummary complete = summary(1L, "example.com", STARTED, "COMPLETED");
        ScanSummary cancelled = summary(2L, "example.com", STARTED, "CANCELLED");
        ScanSummary failed = summary(3L, "example.com", STARTED, "FAILED");

        int hidden = ScanChoices.hiddenCount(List.of(complete, cancelled, failed));
        String note = ScanChoices.hiddenNote(List.of(complete, cancelled, failed));

        assertEquals(2, hidden);
        assertTrue(note.contains("2"));
    }

    @Test
    void hiddenNoteIsEmptyWhenNoneAreHidden() {
        ScanSummary complete = summary(1L, "example.com", STARTED, "COMPLETED");
        assertEquals("", ScanChoices.hiddenNote(List.of(complete)));
        assertEquals(0, ScanChoices.hiddenCount(List.of(complete)));
    }

    @Test
    void anEmptyInputListYieldsEmptyOutputsNotAnException() {
        assertEquals(List.of(), ScanChoices.selectable(List.of(), ZONE));
        assertEquals(List.of(), ScanChoices.dates(List.of()));
        assertEquals(List.of(), ScanChoices.onDate(List.of(), LocalDate.of(2026, 1, 1)));
        assertEquals(0, ScanChoices.hiddenCount(List.of()));
        assertEquals("", ScanChoices.hiddenNote(List.of()));
    }

    @Test
    void theLabelFormatContainsTheTimeAndTheTarget() {
        ScanSummary scan = summary(1L, "example.com", STARTED, "COMPLETED");
        List<ScanChoice> choices = ScanChoices.selectable(List.of(scan), ZONE);

        String label = choices.get(0).label();

        assertTrue(label.contains("example.com"));
        assertTrue(label.contains(String.valueOf(STARTED.atZone(ZONE).getHour())));
    }

    private static ScanSummary summary(long id, String target, Instant startedAt, String status) {
        return new ScanSummary(id, target, startedAt, FINISHED, status);
    }
}
