package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Section 7.2 of the plan: the toolkit-free deterministic radial layout battery. Every node's
 * position is asserted to an EXACT coordinate (plan §0.3's stated test philosophy), never a "no
 * overlap" heuristic. No JavaFX on the classpath.
 */
class GraphLayoutTest {

    private static GraphNode node(String id, String parentId, GraphNodeKind kind) {
        return new GraphNode(id, parentId, kind, id, null, null, null);
    }

    private static GraphNode port(String id, String parentId, String state) {
        return new GraphNode(id, parentId, GraphNodeKind.PORT, id, "PORT", 1, state);
    }

    private static GraphNode leaf(String id, String parentId, String typeToken) {
        return new GraphNode(id, parentId, GraphNodeKind.LEAF, id, typeToken, null, null);
    }

    // ---- 1. root alone ----

    @Test
    void rootAloneIsOnePointAtCenterDepthZeroLabelled() {
        GraphNode root = node("target", null, GraphNodeKind.TARGET);
        GraphModel model = new GraphModel(List.of(root), "target", 0, 0);

        List<GraphPoint> points = GraphLayout.radial(model);

        assertEquals(1, points.size());
        GraphPoint point = points.get(0);
        assertEquals("target", point.nodeId());
        assertEquals(0.5, point.x(), 1e-9);
        assertEquals(0.5, point.y(), 1e-9);
        assertEquals(0, point.depth());
        assertTrue(point.labelled());
    }

    // ---- 2. one child, 12 o'clock ----

    @Test
    void oneChildIsAtTwelveOClock() {
        GraphNode root = node("target", null, GraphNodeKind.TARGET);
        GraphNode child = node("c1", "target", GraphNodeKind.HOST);
        GraphModel model = new GraphModel(List.of(root, child), "target", 0, 0);

        List<GraphPoint> points = GraphLayout.radial(model);
        GraphPoint childPoint = pointFor(points, "c1");

        assertEquals(0.5, childPoint.x(), 1e-9);
        assertEquals(0.5 - GraphLayout.OUTER_RADIUS, childPoint.y(), 1e-9);
    }

    // ---- 3. four equal-weight children, compass points ----

    @Test
    void fourEqualWeightChildrenLandOnTheCompassPointsClockwiseFromTwelve() {
        GraphNode root = node("target", null, GraphNodeKind.TARGET);
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(root);
        nodes.add(node("c1", "target", GraphNodeKind.HOST));
        nodes.add(node("c2", "target", GraphNodeKind.HOST));
        nodes.add(node("c3", "target", GraphNodeKind.HOST));
        nodes.add(node("c4", "target", GraphNodeKind.HOST));
        GraphModel model = new GraphModel(nodes, "target", 0, 0);

        List<GraphPoint> points = GraphLayout.radial(model);
        double r = GraphLayout.OUTER_RADIUS;

        assertPoint(pointFor(points, "c1"), 0.5, 0.5 - r);       // 12 o'clock
        assertPoint(pointFor(points, "c2"), 0.5 + r, 0.5);       // 3 o'clock
        assertPoint(pointFor(points, "c3"), 0.5, 0.5 + r);       // 6 o'clock
        assertPoint(pointFor(points, "c4"), 0.5 - r, 0.5);       // 9 o'clock
    }

    // ---- 4. sector proportionality ----

    @Test
    void sectorsArePartitionedProportionallyToLeafDescendantWeight() {
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(node("target", null, GraphNodeKind.TARGET));
        nodes.add(node("a", "target", GraphNodeKind.HOST));
        nodes.add(node("b", "target", GraphNodeKind.HOST));
        for (int i = 0; i < 10; i++) {
            nodes.add(node("a-leaf-" + i, "a", GraphNodeKind.PORT));
        }
        GraphModel model = new GraphModel(nodes, "target", 0, 0);

        List<GraphPoint> points = GraphLayout.radial(model);
        // A carries weight 10 (10 leaf descendants), B carries weight 1 (minimum 1, no
        // children). A's sector starts the full circle at turn 0; B's sector starts at
        // turn 10/11 -- A therefore occupies 10/11 of the circle.
        double thetaA = angleOfPoint(pointFor(points, "a"));
        double thetaB = angleOfPoint(pointFor(points, "b"));
        assertEquals(0.0, thetaA, 1e-9);
        assertEquals(10.0 / 11.0, thetaB, 1e-9);
    }

    // ---- 5. depths ----

    @Test
    void depthsAreRootZeroHostAndLeafOnePortTwo() {
        List<GraphNode> nodes = List.of(
                node("target", null, GraphNodeKind.TARGET),
                node("host:h", "target", GraphNodeKind.HOST),
                leaf("finding:1", "target", "SUBDOMAIN"),
                port("finding:2", "host:h", "OPEN"));
        GraphModel model = new GraphModel(nodes, "target", 1, 1);

        List<GraphPoint> points = GraphLayout.radial(model);

        assertEquals(0, pointFor(points, "target").depth());
        assertEquals(1, pointFor(points, "host:h").depth());
        assertEquals(1, pointFor(points, "finding:1").depth());
        assertEquals(2, pointFor(points, "finding:2").depth());
    }

    // ---- 6. bounds invariant ----

    @Test
    void everyPointOfALargeModelStaysInsideTheUnitSquare() {
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(node("target", null, GraphNodeKind.TARGET));
        for (int i = 0; i < 299; i++) {
            nodes.add(leaf("finding:" + i, "target", "SUBDOMAIN"));
        }
        GraphModel model = new GraphModel(nodes, "target", 299, 299);

        List<GraphPoint> points = GraphLayout.radial(model);
        assertEquals(300, points.size());
        for (GraphPoint point : points) {
            assertTrue(point.x() >= 0.0 && point.x() <= 1.0, "x out of bounds: " + point);
            assertTrue(point.y() >= 0.0 && point.y() <= 1.0, "y out of bounds: " + point);
        }
    }

    // ---- 7. determinism ----

    @Test
    void radialIsDeterministicAcrossCalls() {
        List<GraphNode> nodes = List.of(
                node("target", null, GraphNodeKind.TARGET),
                node("host:h", "target", GraphNodeKind.HOST),
                port("finding:1", "host:h", "OPEN"),
                leaf("finding:2", "target", "SUBDOMAIN"));
        GraphModel model = new GraphModel(nodes, "target", 1, 1);

        List<GraphPoint> first = GraphLayout.radial(model);
        List<GraphPoint> second = GraphLayout.radial(model);
        assertEquals(first, second);
    }

    // ---- 8. coverage ----

    @Test
    void radialProducesExactlyOnePointPerNodeAndTheIdSetsMatch() {
        List<GraphNode> nodes = List.of(
                node("target", null, GraphNodeKind.TARGET),
                node("host:h", "target", GraphNodeKind.HOST),
                port("finding:1", "host:h", "OPEN"),
                leaf("finding:2", "target", "SUBDOMAIN"));
        GraphModel model = new GraphModel(nodes, "target", 1, 1);

        List<GraphPoint> points = GraphLayout.radial(model);
        assertEquals(nodes.size(), points.size());

        Set<String> nodeIds = new HashSet<>();
        for (GraphNode n : nodes) {
            nodeIds.add(n.id());
        }
        Set<String> pointIds = new HashSet<>();
        for (GraphPoint p : points) {
            pointIds.add(p.nodeId());
        }
        assertEquals(nodeIds, pointIds);
    }

    // ---- 9. label rule boundary ----

    @Test
    void ringOfTwentyFourIsFullyLabelledRingOfTwentyFiveOnlyLabelsTargetAndHost() {
        List<GraphNode> nodes24 = new ArrayList<>();
        nodes24.add(node("target", null, GraphNodeKind.TARGET));
        for (int i = 0; i < 24; i++) {
            nodes24.add(leaf("finding:" + i, "target", "SUBDOMAIN"));
        }
        GraphModel model24 = new GraphModel(nodes24, "target", 24, 24);
        List<GraphPoint> points24 = GraphLayout.radial(model24);
        for (GraphPoint point : points24) {
            if (point.depth() == 1) {
                assertTrue(point.labelled(), "expected " + point + " labelled in a 24-ring");
            }
        }

        List<GraphNode> nodes25 = new ArrayList<>();
        nodes25.add(node("target", null, GraphNodeKind.TARGET));
        nodes25.add(node("host:h", "target", GraphNodeKind.HOST));
        for (int i = 0; i < 24; i++) {
            nodes25.add(leaf("finding:" + i, "target", "SUBDOMAIN"));
        }
        GraphModel model25 = new GraphModel(nodes25, "target", 24, 24);
        List<GraphPoint> points25 = GraphLayout.radial(model25);
        assertTrue(pointFor(points25, "host:h").labelled());
        for (int i = 0; i < 24; i++) {
            assertTrue(!pointFor(points25, "finding:" + i).labelled(),
                    "leaf in a 25-ring must not be labelled");
        }
    }

    // ---- 10. styleClassesFor ----

    @Test
    void styleClassesForEncodesKindAndPortStateAndLeafType() {
        GraphNode openPort = port("p1", "host:h", "OPEN");
        assertEquals(List.of("graph-node", "graph-node-port", "graph-state-open"),
                GraphLayout.styleClassesFor(openPort));

        GraphNode throttledPort = port("p2", "host:h", "THROTTLED");
        assertEquals(List.of("graph-node", "graph-node-port", "graph-state-throttled"),
                GraphLayout.styleClassesFor(throttledPort));

        GraphNode subdomainLeaf = leaf("l1", "target", "SUBDOMAIN");
        assertEquals(List.of("graph-node", "graph-node-leaf", "graph-type-subdomain"),
                GraphLayout.styleClassesFor(subdomainLeaf));

        GraphNode target = node("target", null, GraphNodeKind.TARGET);
        assertEquals(List.of("graph-node", "graph-node-target"),
                GraphLayout.styleClassesFor(target));
    }

    // ---- 11. legend ----

    @Test
    void legendListsOnlyPresentKindsAndStatesInDeterministicOrder() {
        List<GraphNode> nodes = List.of(
                node("target", null, GraphNodeKind.TARGET),
                node("host:h", "target", GraphNodeKind.HOST),
                port("finding:1", "host:h", "OPEN"),
                leaf("finding:2", "target", "SUBDOMAIN"));
        GraphModel model = new GraphModel(nodes, "target", 1, 1);

        List<GraphLegendEntry> legend = GraphLayout.legend(model);

        assertEquals(List.of(
                new GraphLegendEntry("target", "graph-node-target"),
                new GraphLegendEntry("host", "graph-node-host"),
                new GraphLegendEntry("port", "graph-node-port"),
                new GraphLegendEntry("leaf", "graph-node-leaf"),
                new GraphLegendEntry("open", "graph-state-open")), legend);
    }

    @Test
    void legendHasNoEntryForAStateTheScanDoesNotContain() {
        List<GraphNode> nodes = List.of(
                node("target", null, GraphNodeKind.TARGET),
                node("host:h", "target", GraphNodeKind.HOST),
                port("finding:1", "host:h", "OPEN"));
        GraphModel model = new GraphModel(nodes, "target", 0, 0);

        List<GraphLegendEntry> legend = GraphLayout.legend(model);
        for (GraphLegendEntry entry : legend) {
            assertTrue(!entry.styleClass().equals("graph-state-closed"));
        }
    }

    // ---- 12. null arguments ----

    @Test
    void nullArgumentsThrowNpeNeverASilentEmptyList() {
        assertThrows(NullPointerException.class, () -> GraphLayout.radial(null));
        assertThrows(NullPointerException.class, () -> GraphLayout.styleClassesFor(null));
        assertThrows(NullPointerException.class, () -> GraphLayout.legend(null));
    }

    private static void assertPoint(GraphPoint point, double x, double y) {
        assertEquals(x, point.x(), 1e-9, "x mismatch for " + point);
        assertEquals(y, point.y(), 1e-9, "y mismatch for " + point);
    }

    private static GraphPoint pointFor(List<GraphPoint> points, String nodeId) {
        for (GraphPoint point : points) {
            if (point.nodeId().equals(nodeId)) {
                return point;
            }
        }
        throw new AssertionError("no point for node id " + nodeId);
    }

    /** Recovers the turn (θ, in [0,1)) that produced {@code point}'s x/y from the radial formula,
     *  given the known outer radius at depth 1 -- used only to assert exact sector angles. */
    private static double angleOfPoint(GraphPoint point) {
        double r = GraphLayout.OUTER_RADIUS * point.depth();
        double sin = (point.x() - 0.5) / r;
        double cos = (0.5 - point.y()) / r;
        double theta = Math.atan2(sin, cos) / (2 * Math.PI);
        if (theta < 0) {
            theta += 1.0;
        }
        return theta;
    }
}
