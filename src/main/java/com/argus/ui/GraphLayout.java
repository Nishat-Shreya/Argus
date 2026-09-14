package com.argus.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The deterministic radial sector-partitioned tree layout and its render rules (plan §3.6 /
 * §0.3). Pure, stateless, uninstantiable, no toolkit, no clock, no randomness -- a tree has an
 * exact closed-form layout, so this is a one-pass function, never a physics simulation.
 */
final class GraphLayout {

    /** The outer ring's radius in normalized {@code [0,1]} space; the remainder is margin. */
    static final double OUTER_RADIUS = 0.44;

    /** A ring holding more nodes than this shows labels only for {@code TARGET}/{@code HOST}. */
    static final int MAX_LABELS_PER_RING = 24;

    private GraphLayout() {
    }

    /**
     * One point per node, in {@code model.nodes()} order. Root at {@code (0.5, 0.5)}, depth 0.
     * Each node's weight is its leaf-descendant count (minimum 1); a node partitions its angular
     * sector among its children in proportion to their weight, in the model's child order, and
     * each child is placed at the start of its own sub-sector -- so, for example, a lone child of
     * the root lands at turn 0 (12 o'clock) and four equal-weight children land on the four
     * compass points, exactly, confirming the clockwise-from-12 convention.
     */
    static List<GraphPoint> radial(GraphModel model) {
        Objects.requireNonNull(model, "model");
        List<GraphNode> nodes = model.nodes();
        if (nodes.isEmpty()) {
            return List.of();
        }
        GraphNode root = nodes.get(0);

        Map<String, List<GraphNode>> childrenOf = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            if (node.parentId() != null) {
                childrenOf.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
            }
        }

        Map<String, Integer> weight = new HashMap<>();
        computeWeight(root, childrenOf, weight);

        Map<String, Integer> depthOf = new HashMap<>();
        int maxDepth = computeDepths(root, childrenOf, depthOf);
        if (maxDepth < 1) {
            maxDepth = 1;
        }

        Map<String, Double> angleOf = new HashMap<>();
        assignAngles(root, childrenOf, weight, 0.0, 1.0, angleOf);

        Map<Integer, Integer> ringSize = new HashMap<>();
        for (GraphNode node : nodes) {
            ringSize.merge(depthOf.get(node.id()), 1, Integer::sum);
        }

        int finalMaxDepth = maxDepth;
        List<GraphPoint> points = new ArrayList<>();
        for (GraphNode node : nodes) {
            int depth = depthOf.get(node.id());
            double x;
            double y;
            if (depth == 0) {
                x = 0.5;
                y = 0.5;
            } else {
                double r = OUTER_RADIUS * depth / (double) finalMaxDepth;
                double theta = angleOf.getOrDefault(node.id(), 0.0);
                x = 0.5 + r * Math.sin(2 * Math.PI * theta);
                y = 0.5 - r * Math.cos(2 * Math.PI * theta);
            }
            boolean labelled = node.kind() == GraphNodeKind.TARGET
                    || node.kind() == GraphNodeKind.HOST
                    || ringSize.get(depth) <= MAX_LABELS_PER_RING;
            points.add(new GraphPoint(node.id(), x, y, depth, labelled));
        }
        return List.copyOf(points);
    }

    /** CSS classes for {@code node}: {@code "graph-node"}, {@code "graph-node-<kind>"}, and --
     *  for a {@code PORT} -- {@code "graph-state-<state>"}, or -- for a {@code LEAF} -- {@code
     *  "graph-type-<typeToken>"}. An unseen state/type token still gets its own class, flowing
     *  through unmodified (P2-10's {@code ChartData.styleClassFor} rule, one hop out). */
    static List<String> styleClassesFor(GraphNode node) {
        Objects.requireNonNull(node, "node");
        List<String> classes = new ArrayList<>();
        classes.add("graph-node");
        classes.add("graph-node-" + node.kind().name().toLowerCase(Locale.ROOT));
        if (node.kind() == GraphNodeKind.PORT && node.state() != null) {
            classes.add("graph-state-" + node.state().toLowerCase(Locale.ROOT));
        }
        if (node.kind() == GraphNodeKind.LEAF && node.typeToken() != null) {
            classes.add("graph-type-" + node.typeToken().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(classes);
    }

    /** One legend entry per kind actually present (enum order), then one per state token
     *  actually present among {@code PORT} nodes (alphabetical) -- never a hard-coded list. */
    static List<GraphLegendEntry> legend(GraphModel model) {
        Objects.requireNonNull(model, "model");
        List<GraphLegendEntry> entries = new ArrayList<>();

        for (GraphNodeKind kind : GraphNodeKind.values()) {
            if (kindPresent(model, kind)) {
                entries.add(new GraphLegendEntry(kind.name().toLowerCase(Locale.ROOT),
                        "graph-node-" + kind.name().toLowerCase(Locale.ROOT)));
            }
        }

        TreeSet<String> states = new TreeSet<>();
        for (GraphNode node : model.nodes()) {
            if (node.kind() == GraphNodeKind.PORT && node.state() != null) {
                states.add(node.state());
            }
        }
        for (String state : states) {
            entries.add(new GraphLegendEntry(state.toLowerCase(Locale.ROOT),
                    "graph-state-" + state.toLowerCase(Locale.ROOT)));
        }

        return List.copyOf(entries);
    }

    private static boolean kindPresent(GraphModel model, GraphNodeKind kind) {
        for (GraphNode node : model.nodes()) {
            if (node.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static int computeWeight(GraphNode node, Map<String, List<GraphNode>> childrenOf,
            Map<String, Integer> weight) {
        List<GraphNode> children = childrenOf.getOrDefault(node.id(), List.of());
        if (children.isEmpty()) {
            weight.put(node.id(), 1);
            return 1;
        }
        int total = 0;
        for (GraphNode child : children) {
            total += computeWeight(child, childrenOf, weight);
        }
        weight.put(node.id(), total);
        return total;
    }

    private static int computeDepths(GraphNode root, Map<String, List<GraphNode>> childrenOf,
            Map<String, Integer> depthOf) {
        depthOf.put(root.id(), 0);
        int max = 0;
        Deque<GraphNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            GraphNode current = stack.pop();
            int depth = depthOf.get(current.id());
            max = Math.max(max, depth);
            for (GraphNode child : childrenOf.getOrDefault(current.id(), List.of())) {
                depthOf.put(child.id(), depth + 1);
                stack.push(child);
            }
        }
        return max;
    }

    /**
     * Partitions {@code [a0, a1)} among {@code node}'s children, in proportion to their weight
     * and in the model's child order. Each child is placed at the START of its own sub-sector --
     * not its midpoint -- which is what makes a lone child of the root land exactly at turn 0
     * (12 o'clock) and four equal-weight children land exactly on the four compass points.
     */
    private static void assignAngles(GraphNode node, Map<String, List<GraphNode>> childrenOf,
            Map<String, Integer> weight, double a0, double a1, Map<String, Double> angleOf) {
        List<GraphNode> children = childrenOf.getOrDefault(node.id(), List.of());
        if (children.isEmpty()) {
            return;
        }
        int totalWeight = 0;
        for (GraphNode child : children) {
            totalWeight += weight.get(child.id());
        }
        double span = a1 - a0;
        double cumulative = a0;
        for (GraphNode child : children) {
            double childSpan = span * weight.get(child.id()) / (double) totalWeight;
            angleOf.put(child.id(), normalizeTurn(cumulative));
            assignAngles(child, childrenOf, weight, cumulative, cumulative + childSpan, angleOf);
            cumulative += childSpan;
        }
    }

    private static double normalizeTurn(double turn) {
        double normalized = turn % 1.0;
        if (normalized < 0) {
            normalized += 1.0;
        }
        return normalized;
    }
}
