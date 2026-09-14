package com.argus.ui;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps persisted {@link FindingSnapshot}s onto {@link FindingRow} (plan §3.4). Pure,
 * toolkit-free. Reuses the existing {@code FindingRow} record byte-identical -- {@code
 * FindingRows} is not modified and not overloaded, since it maps live {@code
 * PortResult}/{@code Subdomain}, not persisted {@code FindingSnapshot}s.
 */
final class SnapshotRows {

    private SnapshotRows() {
    }

    /**
     * Probes first, then the rest. Probe order: subject asc (Locale.ROOT), then port NUMERIC
     * asc. Non-probe order: type asc, then subject asc.
     */
    static List<FindingRow> of(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");

        List<FindingSnapshot> probes = new ArrayList<>();
        List<FindingSnapshot> nonProbes = new ArrayList<>();
        for (FindingSnapshot finding : findings) {
            if (isProbe(finding)) {
                probes.add(finding);
            } else {
                nonProbes.add(finding);
            }
        }

        probes.sort(Comparator
                .comparing((FindingSnapshot f) -> f.subject().toLowerCase(Locale.ROOT))
                .thenComparingInt(FindingSnapshot::port));
        nonProbes.sort(Comparator
                .comparing((FindingSnapshot f) -> f.type().toLowerCase(Locale.ROOT))
                .thenComparing(f -> f.subject().toLowerCase(Locale.ROOT)));

        List<FindingRow> rows = new ArrayList<>();
        for (FindingSnapshot probe : probes) {
            rows.add(toRow(probe));
        }
        for (FindingSnapshot nonProbe : nonProbes) {
            rows.add(toRow(nonProbe));
        }
        return List.copyOf(rows);
    }

    /** A finding is a "probe" iff {@code port() != null && state() != null} (P2-11's rule). */
    private static boolean isProbe(FindingSnapshot finding) {
        return finding.port() != null && finding.state() != null;
    }

    private static FindingRow toRow(FindingSnapshot finding) {
        String type = finding.type().toLowerCase(Locale.ROOT);
        String subject = finding.subject();
        String port = finding.port() == null ? "" : String.valueOf(finding.port());
        String state = finding.state() == null ? "" : finding.state().toLowerCase(Locale.ROOT);
        return new FindingRow(type, subject, port, state);
    }
}
