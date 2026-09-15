package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanSummary;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link ReportModel} and its suggested filename (plan §3.2). {@code ZoneId} is passed
 * in, never read here -- the {@code ScanChoices}/{@code Timelines} contract. Reuses {@link
 * ChartData#summaryLine} and {@link SnapshotRows#of} verbatim -- the project's single
 * authorities for summarising and row-shaping a persisted scan -- so this class owns no counting
 * or row-shaping rule of its own.
 */
final class Reports {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter FILE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Reports() {
    }

    /** Builds the model from one persisted scan and its findings, at a caller-supplied zone. */
    static ReportModel model(ScanSummary scan, List<FindingSnapshot> findings, ZoneId zone) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(zone, "zone");

        String startedAt = DISPLAY_FORMAT.format(scan.startedAt().atZone(zone));
        String finishedAt =
                scan.finishedAt() == null ? "" : DISPLAY_FORMAT.format(scan.finishedAt().atZone(zone));
        String summaryLine = ChartData.summaryLine(findings);
        List<FindingRow> rows = SnapshotRows.of(findings);

        return new ReportModel(scan.id(), scan.target(), startedAt, finishedAt, summaryLine, rows);
    }

    /** {@code "argus-example.com-20260914-143207.html"} -- from the SCAN's startedAt, never
     *  {@code now()}. The target is sanitized to {@code [a-z0-9.-]}, collapsing anything else
     *  to {@code -}, since a filename built from stored text must not be able to contain a path
     *  separator. */
    static String suggestedFileName(ReportModel model) {
        Objects.requireNonNull(model, "model");
        String sanitizedTarget = sanitize(model.target());
        return "argus-" + sanitizedTarget + "-" + fileTimestamp(model) + ".html";
    }

    /** {@code ""} when {@code hiddenCount == 0}, else the exact sentence the screen shows. */
    static String hiddenNote(int hiddenCount) {
        if (hiddenCount == 0) {
            return "";
        }
        return hiddenCount + " scans hidden — cancelled or failed scans are partial and are not "
                + "exported";
    }

    private static String fileTimestamp(ReportModel model) {
        // ReportModel.startedAt() is already the display-formatted "yyyy-MM-dd HH:mm:ss" string
        // for the SCAN's own startedAt -- re-parsing it back into the file-name format keeps the
        // filename derived from the scan's timestamp, never a fresh clock read (plan §5.4).
        String startedAt = model.startedAt();
        String datePart = startedAt.substring(0, 10).replace("-", "");
        String timePart = startedAt.substring(11).replace(":", "");
        return datePart + "-" + timePart;
    }

    private static String sanitize(String target) {
        StringBuilder sanitized = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char c = Character.toLowerCase(target.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-') {
                sanitized.append(c);
            } else {
                sanitized.append('-');
            }
        }
        return sanitized.toString();
    }
}
