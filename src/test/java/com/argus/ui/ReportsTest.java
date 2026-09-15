package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanSummary;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Section 7.1: {@code Reports} -- toolkit-free model/filename/note construction (plan §3.2).
 * Fixed {@code ZoneId} only, never {@code ZoneId.systemDefault()}, never a clock.
 */
class ReportsTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant STARTED = Instant.parse("2026-09-14T14:32:07Z");
    private static final Instant FINISHED = Instant.parse("2026-09-14T14:40:00Z");

    @Test
    void r1HappyPathReusesChartDataSummaryLineAndSnapshotRows() {
        ScanSummary scan = new ScanSummary(7L, "example.com", STARTED, FINISHED, "COMPLETED");
        List<FindingSnapshot> findings = List.of(
                new FindingSnapshot(1L, "PORT", "example.com", 80, "OPEN"),
                new FindingSnapshot(2L, "PORT", "example.com", 443, "CLOSED"),
                new FindingSnapshot(3L, "SUBDOMAIN", "www.example.com", null, null));

        ReportModel model = Reports.model(scan, findings, ZONE);

        assertEquals(7L, model.scanId());
        assertEquals("example.com", model.target());
        assertEquals("2026-09-14 14:32:07", model.startedAt());
        assertEquals(ChartData.summaryLine(findings), model.summaryLine());
        assertEquals(SnapshotRows.of(findings), model.rows());
    }

    @Test
    void r2ARunningScanWithNoFinishedAtYieldsAnEmptyStringNotTheWordNull() {
        ScanSummary running = new ScanSummary(1L, "example.com", STARTED, null, "RUNNING");

        ReportModel model = Reports.model(running, List.of(), ZONE);

        assertEquals("", model.finishedAt());
    }

    @Test
    void r3AnEmptyFindingListYieldsZeroCountAndTheFixedNoFindingsSentence() {
        ScanSummary scan = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");

        ReportModel model = Reports.model(scan, List.of(), ZONE);

        assertEquals(0, model.findingCount());
        assertEquals("no findings recorded for this scan", model.summaryLine());
    }

    @Test
    void r4SuggestedFileNameExactValue() {
        ScanSummary scan = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        ReportModel model = Reports.model(scan, List.of(), ZONE);

        String fileName = Reports.suggestedFileName(model);

        assertEquals("argus-example.com-20260914-143207.html", fileName);
    }

    @Test
    void r5SuggestedFileNameSanitizesTheTarget() {
        ScanSummary scan =
                new ScanSummary(1L, "../weird target\\name", STARTED, FINISHED, "COMPLETED");
        ReportModel model = Reports.model(scan, List.of(), ZONE);

        String fileName = Reports.suggestedFileName(model);

        assertFalse(fileName.contains("/"));
        assertFalse(fileName.contains("\\"));
        assertFalse(fileName.contains(" "));
        assertTrue(Pattern.matches("argus-[a-z0-9.-]+-\\d{8}-\\d{6}\\.html", fileName),
                "unexpected file name: " + fileName);
    }

    @Test
    void r6SuggestedFileNameUsesTheScansTimestampNeverNow() {
        ScanSummary scan2019 = new ScanSummary(1L, "example.com",
                Instant.parse("2019-03-05T09:00:00Z"), null, "COMPLETED");
        ReportModel model = Reports.model(scan2019, List.of(), ZONE);

        String first = Reports.suggestedFileName(model);
        String second = Reports.suggestedFileName(model);

        assertEquals(first, second);
        assertTrue(first.contains("20190305-090000"));
    }

    @Test
    void r7HiddenNoteIsBlankAtZeroAndExactSentenceOtherwise() {
        assertEquals("", Reports.hiddenNote(0));

        String note = Reports.hiddenNote(3);
        assertEquals("3 scans hidden — cancelled or failed scans are partial and are not "
                + "exported", note);
    }

    @Test
    void r8TimezoneIsAParameterNotAnEnvironmentRead() {
        ScanSummary scan = new ScanSummary(1L, "example.com",
                Instant.parse("2026-01-01T02:30:00Z"), null, "COMPLETED");

        ReportModel utc = Reports.model(scan, List.of(), ZoneOffset.UTC);
        ReportModel offset = Reports.model(scan, List.of(), ZoneId.of("America/New_York"));

        assertFalse(utc.startedAt().equals(offset.startedAt()));
    }

    @Test
    void r9ReportModelRejectsNullsAndDefensivelyCopiesRows() {
        assertThrows(NullPointerException.class,
                () -> new ReportModel(1L, null, "", "", "x", List.of()));
        assertThrows(NullPointerException.class,
                () -> new ReportModel(1L, "x", null, "", "x", List.of()));
        assertThrows(NullPointerException.class,
                () -> new ReportModel(1L, "x", "", null, "x", List.of()));
        assertThrows(NullPointerException.class,
                () -> new ReportModel(1L, "x", "", "", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ReportModel(1L, "x", "", "", "x", null));

        List<FindingRow> source = new ArrayList<>();
        source.add(new FindingRow("port", "a.example.com", "80", "open"));
        ReportModel model = new ReportModel(1L, "x", "", "", "x", source);
        source.add(new FindingRow("port", "b.example.com", "81", "open"));

        assertEquals(1, model.rows().size());
        assertThrows(UnsupportedOperationException.class,
                () -> model.rows().add(new FindingRow("port", "c.example.com", "82", "open")));
    }

    @Test
    void everyFactoryRejectsNull() {
        ScanSummary scan = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        assertThrows(NullPointerException.class, () -> Reports.model(null, List.of(), ZONE));
        assertThrows(NullPointerException.class, () -> Reports.model(scan, null, ZONE));
        assertThrows(NullPointerException.class, () -> Reports.model(scan, List.of(), null));
        assertThrows(NullPointerException.class, () -> Reports.suggestedFileName(null));
    }
}
