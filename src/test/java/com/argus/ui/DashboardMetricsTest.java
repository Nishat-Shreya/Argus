package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardMetricsTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @Test
    void totalsAndFindingsByTypeAndStateAreSummedAcrossScans() {
        ScanSummary scan1 = new ScanSummary(1L, "example.com",
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:05:00Z"),
                "COMPLETED");
        ScanSummary scan2 = new ScanSummary(2L, "other.com",
                Instant.parse("2026-09-20T10:00:00Z"), Instant.parse("2026-09-20T10:05:00Z"),
                "COMPLETED");

        Map<Long, List<FindingSnapshot>> findings = Map.of(
                1L, List.of(
                        new FindingSnapshot(1L, "PORT", "example.com", 80, "OPEN"),
                        new FindingSnapshot(2L, "PORT", "example.com", 22, "FILTERED")),
                2L, List.of(
                        new FindingSnapshot(3L, "SUBDOMAIN", "a.other.com", null, null)));

        DashboardMetrics metrics =
                DashboardMetrics.of(List.of(scan1, scan2), findings, 3, TODAY, ZONE);

        assertEquals(2, metrics.totalScans());
        assertEquals(3, metrics.totalFindings());
        assertEquals(3, metrics.scheduledScansCount());
        assertEquals(2, metrics.findingsByType().get("PORT"));
        assertEquals(1, metrics.findingsByType().get("SUBDOMAIN"));
        assertEquals(1, metrics.findingsByState().get("OPEN"));
        assertEquals(1, metrics.findingsByState().get("FILTERED"));
    }

    @Test
    void recentScansAreCappedAtTheLimitAndPreserveOrder() {
        List<ScanSummary> scans = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            scans.add(new ScanSummary(i, "target" + i + ".com",
                    Instant.parse("2026-09-2" + (1 - i / 10) + "T10:00:00Z"),
                    Instant.parse("2026-09-2" + (1 - i / 10) + "T10:05:00Z"), "COMPLETED"));
        }

        DashboardMetrics metrics = DashboardMetrics.of(scans, Map.of(), 0, TODAY, ZONE);

        assertEquals(DashboardMetrics.RECENT_SCAN_LIMIT, metrics.recentScans().size());
        assertEquals("target1.com", metrics.recentScans().get(0).target());
    }

    @Test
    void aScanWithNoFindingsEntryContributesZero() {
        ScanSummary scan = new ScanSummary(1L, "example.com",
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:05:00Z"),
                "COMPLETED");

        DashboardMetrics metrics = DashboardMetrics.of(List.of(scan), Map.of(), 0, TODAY, ZONE);

        assertEquals(0, metrics.totalFindings());
        assertEquals(0, metrics.recentScans().get(0).findingCount());
    }

    @Test
    void statusIsDisplayedLowercase() {
        ScanSummary scan = new ScanSummary(1L, "example.com",
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:05:00Z"),
                "FAILED");

        DashboardMetrics metrics = DashboardMetrics.of(List.of(scan), Map.of(), 0, TODAY, ZONE);

        assertEquals("failed", metrics.recentScans().get(0).status());
    }

    @Test
    void trendCoversExactlySevenDaysEndingToday() {
        DashboardMetrics metrics = DashboardMetrics.of(List.of(), Map.of(), 0, TODAY, ZONE);

        assertEquals(DashboardMetrics.TREND_DAYS, metrics.trend().size());
        assertEquals(TODAY.minusDays(6), metrics.trend().get(0).date());
        assertEquals(TODAY, metrics.trend().get(metrics.trend().size() - 1).date());
        for (DashboardMetrics.TrendPoint point : metrics.trend()) {
            assertEquals(0, point.scanCount());
        }
    }

    @Test
    void trendCountsAScanOnTheDayItStarted() {
        ScanSummary scanToday = new ScanSummary(1L, "example.com",
                Instant.parse("2026-09-21T10:00:00Z"), Instant.parse("2026-09-21T10:05:00Z"),
                "COMPLETED");
        ScanSummary scanOutsideWindow = new ScanSummary(2L, "old.com",
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:05:00Z"),
                "COMPLETED");

        DashboardMetrics metrics = DashboardMetrics.of(
                List.of(scanToday, scanOutsideWindow), Map.of(), 0, TODAY, ZONE);

        DashboardMetrics.TrendPoint last = metrics.trend().get(metrics.trend().size() - 1);
        assertEquals(TODAY, last.date());
        assertEquals(1, last.scanCount());
        int totalCounted = metrics.trend().stream().mapToInt(DashboardMetrics.TrendPoint::scanCount).sum();
        assertEquals(1, totalCounted, "the scan outside the 7-day window must not be counted");
    }

    @Test
    void emptyInputsProduceZeroedMetricsNotAnException() {
        DashboardMetrics metrics = DashboardMetrics.of(List.of(), Map.of(), 0, TODAY, ZONE);

        assertEquals(0, metrics.totalScans());
        assertEquals(0, metrics.totalFindings());
        assertTrue(metrics.recentScans().isEmpty());
        assertTrue(metrics.findingsByType().isEmpty());
        assertTrue(metrics.findingsByState().isEmpty());
    }
}
