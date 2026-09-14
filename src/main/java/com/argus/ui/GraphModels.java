package com.argus.ui;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The one builder of a {@link GraphModel} from a scan's target and persisted findings (plan
 * §3.4). Pure, stateless, uninstantiable -- every counting, grouping, ordering and capping rule
 * the graph screen needs lives here, not in the controller.
 *
 * <p>A finding is a "probe" iff {@code port() != null && state() != null} (P2-10's rule,
 * tightened by one conjunct so a malformed row cannot produce a port node with no port).
 * Everything else is a leaf -- a malformed half-null row becomes a leaf, never an exception and
 * never a dropped row.
 */
final class GraphModels {

    /** The leaf/subdomain render cap (plan §3.4, R10). Port/host/target nodes are never capped. */
    static final int MAX_LEAF_NODES = 250;

    private GraphModels() {
    }

    /** Builds the tree: root, then hosts+ports (probe findings), then leaves (everything else). */
    static GraphModel of(String target, List<FindingSnapshot> findings) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(findings, "findings");
        if (target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }

        GraphNode root = new GraphNode("target", null, GraphNodeKind.TARGET, target, null, null,
                null);

        // subject (verbatim) -> its probe findings; TreeMap gives alphabetical, case-sensitive
        // (verbatim) key order -- deliberate, matches P2-08 R3 and the DB's own identity index.
        Map<String, List<FindingSnapshot>> probesByHost = new TreeMap<>();
        List<FindingSnapshot> leafFindings = new ArrayList<>();
        for (FindingSnapshot finding : findings) {
            if (finding.port() != null && finding.state() != null) {
                probesByHost.computeIfAbsent(finding.subject(), key -> new ArrayList<>())
                        .add(finding);
            } else {
                leafFindings.add(finding);
            }
        }

        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(root);

        // depth 1: hosts, alphabetical by subject (TreeMap's key order)
        List<GraphNode> hostNodes = new ArrayList<>();
        for (String subject : probesByHost.keySet()) {
            hostNodes.add(new GraphNode("host:" + subject, root.id(), GraphNodeKind.HOST, subject,
                    null, null, null));
        }
        nodes.addAll(hostNodes);

        // depth 1: leaves, alphabetical by subject then capped at MAX_LEAF_NODES
        leafFindings.sort(Comparator.comparing(FindingSnapshot::subject)
                .thenComparingLong(FindingSnapshot::id));
        int totalLeafCount = leafFindings.size();
        int renderedLeafCount = Math.min(totalLeafCount, MAX_LEAF_NODES);
        List<FindingSnapshot> renderedLeaves = leafFindings.subList(0, renderedLeafCount);
        for (FindingSnapshot leaf : renderedLeaves) {
            nodes.add(new GraphNode("finding:" + leaf.id(), root.id(), GraphNodeKind.LEAF,
                    leaf.subject(), leaf.type(), null, null));
        }

        // depth 2: ports, per host (host order), sorted by port ascending then by state (numeric,
        // never lexicographic)
        for (String subject : probesByHost.keySet()) {
            String hostId = "host:" + subject;
            List<FindingSnapshot> probes = new ArrayList<>(probesByHost.get(subject));
            probes.sort(Comparator.comparingInt(FindingSnapshot::port)
                    .thenComparing(FindingSnapshot::state)
                    .thenComparingLong(FindingSnapshot::id));
            for (FindingSnapshot probe : probes) {
                nodes.add(new GraphNode("finding:" + probe.id(), hostId, GraphNodeKind.PORT,
                        String.valueOf(probe.port()), probe.type(), probe.port(), probe.state()));
            }
        }

        return new GraphModel(List.copyOf(nodes), target, renderedLeafCount, totalLeafCount);
    }

    /** The details-panel sentence for {@code node} (plan §3.4), pure and testable. */
    static String describe(GraphModel model, GraphNode node) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(node, "node");
        return switch (node.kind()) {
            case TARGET -> "scan target · " + node.label();
            case HOST -> "host probed · " + node.label();
            case PORT -> "port " + node.port() + " · state "
                    + node.state().toLowerCase(Locale.ROOT) + " · host " + hostLabel(model, node);
            case LEAF -> node.typeToken().toLowerCase(Locale.ROOT) + " · " + node.label();
        };
    }

    private static String hostLabel(GraphModel model, GraphNode portNode) {
        for (GraphNode candidate : model.nodes()) {
            if (candidate.id().equals(portNode.parentId())) {
                return candidate.label();
            }
        }
        throw new IllegalStateException("no host node found for id " + portNode.parentId());
    }
}
