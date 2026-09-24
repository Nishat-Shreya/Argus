package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the redesigned dashboard's metric cards, charts and recent-scans table show,
 * pre-computed from real, already-persisted data (P3-15's plan: "never fabricate dashboard
 * data"). Pure, toolkit-free -- the {@code ScanChoices}/{@code Reports} precedent. No {@code
 * Instant.now()}: {@link #of} takes the scan list and the reference date already resolved by
 * its caller, exactly as {@code Timelines}/{@code Reports} take their {@code ZoneId}.
 *
 * Deliberately does NOT include a unique-tag count or a top-tags list: no existing {@code core}
 * API returns "every tag name across the whole app" (P3-07 explicitly rejected that method as
 * unneeded at the time) -- adding one is a small, safe, additive change, but it is a real {@code
 * core}/{@code db} change, so it is left for an explicit follow-up rather than done silently
 * inside a UI batch.
 */
record DashboardMetrics(
        int totalScans,
        int totalFindings,
        int scheduledScansCount,
        List<RecentScanRow> recentScans,
        List<TrendPoint> trend,
        Map<String, Integer> findingsByType,
        Map<String, Integer> findingsByState) {

    static final int RECENT_SCAN_LIMIT = 6;
    static final int TREND_DAYS = 7;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * @param scans           every persisted scan, newest-first (the {@code ScanHistory}
     *                        contract)
     * @param findingsByScan  each scan's findings, already fetched off the FX thread; a scan
     *                        with no entry contributes zero findings (kept simple: the caller
     *                        may choose to fetch only a bounded recent window, not every scan
     *                        ever run)
     * @param scheduledScansCount already-fetched count, not re-derived here
     * @param today           the reference date for the trend window, resolved by the caller
     * @param zone            resolved once by the caller, never read here
     */
    static DashboardMetrics of(List<ScanSummary> scans,
            Map<Long, List<FindingSnapshot>> findingsByScan, int scheduledScansCount,
            LocalDate today, ZoneId zone) {
        Objects.requireNonNull(scans, "scans");
        Objects.requireNonNull(findingsByScan, "findingsByScan");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(zone, "zone");

        int totalFindings = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (List<FindingSnapshot> findings : findingsByScan.values()) {
            for (FindingSnapshot finding : findings) {
                totalFindings++;
                byType.merge(finding.type(), 1, Integer::sum);
                if (finding.state() != null) {
                    byState.merge(finding.state(), 1, Integer::sum);
                }
            }
        }

        List<RecentScanRow> recent = new ArrayList<>();
        for (int i = 0; i < scans.size() && i < RECENT_SCAN_LIMIT; i++) {
            ScanSummary scan = scans.get(i);
            int findingCount = findingsByScan.getOrDefault(scan.id(), List.of()).size();
            recent.add(new RecentScanRow(
                    scan.id(), scan.target(), displayStatus(scan.status()), findingCount,
                    TIMESTAMP_FORMAT.format(scan.startedAt().atZone(zone))));
        }

        List<TrendPoint> trend = trendOf(scans, today, zone);

        return new DashboardMetrics(scans.size(), totalFindings, scheduledScansCount,
                List.copyOf(recent), trend, Map.copyOf(byType), Map.copyOf(byState));
    }

    private static List<TrendPoint> trendOf(List<ScanSummary> scans, LocalDate today,
            ZoneId zone) {
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), 0);
        }
        for (ScanSummary scan : scans) {
            LocalDate day = scan.startedAt().atZone(zone).toLocalDate();
            if (byDay.containsKey(day)) {
                byDay.merge(day, 1, Integer::sum);
            }
        }
        List<TrendPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> entry : byDay.entrySet()) {
            points.add(new TrendPoint(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(points);
    }

    /** {@code "COMPLETED"} -&gt; {@code "completed"}, etc. -- title case for display, the raw
     *  token otherwise never leaks past {@code core.ScanSummary} into a screen already. */
    private static String displayStatus(String rawStatus) {
        return rawStatus.toLowerCase(java.util.Locale.ROOT);
    }

    record RecentScanRow(long scanId, String target, String status, int findingCount,
            String startedAt) {
    }

    record TrendPoint(LocalDate date, int scanCount) {
    }
}
