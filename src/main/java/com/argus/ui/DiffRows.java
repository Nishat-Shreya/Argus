package com.argus.ui;

import com.argus.core.FindingDelta;
import com.argus.core.FindingSnapshot;
import com.argus.core.ScanDiffReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps a {@code ScanDiffReport} onto {@link DiffRow}. Pure, toolkit-free — the only place the
 * cell-rendering rules of plan §4.4 exist.
 */
final class DiffRows {

    private DiffRows() {
    }

    /** Added, then removed, then changed, each preserving {@code ScanDiffReport}'s (and so
     *  {@code ScanDiff}'s) contractual ordering. */
    static List<DiffRow> of(ScanDiffReport report) {
        Objects.requireNonNull(report, "report");
        List<DiffRow> rows = new ArrayList<>();
        for (FindingSnapshot added : report.added()) {
            rows.add(new DiffRow("added", type(added), added.subject(), port(added), "",
                    side(added)));
        }
        for (FindingSnapshot removed : report.removed()) {
            rows.add(new DiffRow("removed", type(removed), removed.subject(), port(removed),
                    side(removed), ""));
        }
        for (FindingDelta changed : report.changed()) {
            FindingSnapshot baseline = changed.baseline();
            FindingSnapshot current = changed.current();
            rows.add(new DiffRow("changed", type(current), current.subject(), port(current),
                    side(baseline), side(current)));
        }
        return List.copyOf(rows);
    }

    /** {@code "+12 added · -3 removed · 5 changed"}, or a fixed "no changes" message
     *  when {@code report.isEmpty()}. */
    static String summaryLine(ScanDiffReport report) {
        Objects.requireNonNull(report, "report");
        if (report.isEmpty()) {
            return "no changes between these two scans";
        }
        return "+" + report.added().size() + " added · -" + report.removed().size()
                + " removed · " + report.changed().size() + " changed";
    }

    private static String type(FindingSnapshot snapshot) {
        return snapshot.type().toLowerCase(Locale.ROOT);
    }

    private static String port(FindingSnapshot snapshot) {
        return snapshot.port() == null ? "" : String.valueOf(snapshot.port());
    }

    /** A present side's rendering: its state, or {@code "present"} when the state is null (a
     *  subdomain has no state, and a blank there would be indistinguishable from the absent
     *  side). */
    private static String side(FindingSnapshot snapshot) {
        return snapshot.state() == null ? "present" : snapshot.state().toLowerCase(Locale.ROOT);
    }
}
