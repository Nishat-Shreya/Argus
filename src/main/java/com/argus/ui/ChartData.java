package com.argus.ui;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, stateless, uninstantiable chart data preparation (plan §4.2) -- every counting,
 * grouping, ordering and labelling rule the charts screen needs lives here, not in the
 * controller. A finding is a "probe" iff {@code state() != null}; {@link #portBars} additionally
 * requires {@code port() != null}. State labels, slice order and series order are read from the
 * data and sorted alphabetically as strings -- never from a hard-coded list of three -- so a
 * state token this codebase has never seen still renders as its own slice/series/bar.
 */
final class ChartData {

    private ChartData() {
    }

    /** Pie data: one slice per distinct state token among PROBE findings, alphabetical by
     *  token. Empty when the scan has no probes. */
    static List<ChartSlice> portStateSlices(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");
        Map<String, Integer> counts = new TreeMap<>();
        for (FindingSnapshot finding : findings) {
            if (finding.state() != null) {
                counts.merge(finding.state(), 1, Integer::sum);
            }
        }
        List<ChartSlice> slices = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            slices.add(new ChartSlice(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(slices);
    }

    /** Stacked-bar data: one entry per (port, state) with its count; ports ascending, then
     *  state alphabetical. Findings with a null port or a null state are skipped. */
    static List<ChartBar> portBars(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");
        Map<Integer, Map<String, Integer>> counts = new TreeMap<>();
        for (FindingSnapshot finding : findings) {
            if (finding.state() == null || finding.port() == null) {
                continue;
            }
            counts.computeIfAbsent(finding.port(), key -> new TreeMap<>())
                    .merge(finding.state(), 1, Integer::sum);
        }
        List<ChartBar> bars = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> byPort : counts.entrySet()) {
            for (Map.Entry<String, Integer> byState : byPort.getValue().entrySet()) {
                bars.add(new ChartBar(byPort.getKey(), byState.getKey(), byState.getValue()));
            }
        }
        return List.copyOf(bars);
    }

    /** The CategoryAxis order: every port in {@code bars}, ascending, as a String. */
    static List<String> portCategories(List<ChartBar> bars) {
        Objects.requireNonNull(bars, "bars");
        TreeSet<Integer> ports = new TreeSet<>();
        for (ChartBar bar : bars) {
            ports.add(bar.port());
        }
        List<String> categories = new ArrayList<>();
        for (Integer port : ports) {
            categories.add(String.valueOf(port));
        }
        return List.copyOf(categories);
    }

    /** The series order: distinct state tokens in {@code bars}, alphabetical. */
    static List<String> stateSeries(List<ChartBar> bars) {
        Objects.requireNonNull(bars, "bars");
        TreeSet<String> states = new TreeSet<>();
        for (ChartBar bar : bars) {
            states.add(bar.state());
        }
        return List.copyOf(new ArrayList<>(states));
    }

    /** NumberAxis upper bound: the largest per-port stacked total, at least 1. */
    static int barAxisUpperBound(List<ChartBar> bars) {
        Objects.requireNonNull(bars, "bars");
        Map<Integer, Integer> totals = new TreeMap<>();
        for (ChartBar bar : bars) {
            totals.merge(bar.port(), bar.count(), Integer::sum);
        }
        int max = 1;
        for (int total : totals.values()) {
            if (total > max) {
                max = total;
            }
        }
        return max;
    }

    /** "21 port probes · closed 16 · filtered 2 · open 3 · subdomain 57", or a fixed
     *  "no findings" sentence for an empty scan. */
    static String summaryLine(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");
        if (findings.isEmpty()) {
            return "no findings recorded for this scan";
        }

        List<ChartSlice> slices = portStateSlices(findings);
        int probeTotal = 0;
        for (ChartSlice slice : slices) {
            probeTotal += slice.count();
        }

        Map<String, Integer> typeGroups = new TreeMap<>();
        for (FindingSnapshot finding : findings) {
            if (finding.state() == null) {
                typeGroups.merge(finding.type().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        StringBuilder line = new StringBuilder();
        line.append(probeTotal).append(" port probes");
        for (ChartSlice slice : slices) {
            line.append(" · ").append(slice.state().toLowerCase(Locale.ROOT))
                    .append(' ').append(slice.count());
        }
        for (Map.Entry<String, Integer> group : typeGroups.entrySet()) {
            line.append(" · ").append(group.getKey()).append(' ').append(group.getValue());
        }
        return line.toString();
    }

    /** True when at least one finding is a probe -- the charts are hidden when false. */
    static boolean hasProbeFindings(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");
        for (FindingSnapshot finding : findings) {
            if (finding.state() != null) {
                return true;
            }
        }
        return false;
    }

    /** "chart-state-" + token.toLowerCase(Locale.ROOT). The one colour-mapping rule. */
    static String styleClassFor(String stateToken) {
        Objects.requireNonNull(stateToken, "stateToken");
        return "chart-state-" + stateToken.toLowerCase(Locale.ROOT);
    }
}
