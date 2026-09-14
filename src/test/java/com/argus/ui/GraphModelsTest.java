package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 7.1 of the plan: the toolkit-free graph-model-building battery. Imports only
 * {@code java.*} and {@code com.argus.core.FindingSnapshot} -- runs with zero JavaFX on the
 * classpath.
 */
class GraphModelsTest {

    private static FindingSnapshot port(long id, String subject, int port, String state) {
        return new FindingSnapshot(id, "PORT", subject, port, state);
    }

    private static FindingSnapshot leaf(long id, String type, String subject) {
        return new FindingSnapshot(id, type, subject, null, null);
    }

    private static FindingSnapshot subdomain(long id, String subject) {
        return leaf(id, "SUBDOMAIN", subject);
    }

    // ---- 1. empty ----

    @Test
    void emptyFindingsProduceExactlyTheRootNode() {
        GraphModel model = GraphModels.of("example.com", List.of());

        assertEquals(1, model.nodes().size());
        GraphNode root = model.nodes().get(0);
        assertEquals("target", root.id());
        assertEquals(GraphNodeKind.TARGET, root.kind());
        assertNull(root.parentId());
        assertEquals("example.com", root.label());
        assertEquals(0, model.totalLeafCount());
        assertFalse(model.isTruncated());
    }

    // ---- 2. happy path, probes only ----

    @Test
    void probesOnlyProducesRootHostAndPortNodes() {
        List<FindingSnapshot> findings = List.of(
                port(1, "example.com", 443, "OPEN"),
                port(2, "example.com", 22, "OPEN"),
                port(3, "example.com", 80, "CLOSED"));
        GraphModel model = GraphModels.of("example.com", findings);

        assertEquals(5, model.nodes().size());
        GraphNode root = model.nodes().get(0);
        GraphNode host = model.nodes().get(1);
        assertEquals(GraphNodeKind.HOST, host.kind());
        assertEquals("host:example.com", host.id());
        assertEquals(root.id(), host.parentId());

        for (GraphNode node : model.nodes().subList(2, 5)) {
            assertEquals(GraphNodeKind.PORT, node.kind());
            assertEquals(host.id(), node.parentId());
        }
    }

    // ---- 3. happy path, leaves only ----

    @Test
    void leavesOnlyProducesThreeLeafNodesParentedToTarget() {
        List<FindingSnapshot> findings = List.of(
                subdomain(1, "a.example.com"),
                subdomain(2, "b.example.com"),
                subdomain(3, "c.example.com"));
        GraphModel model = GraphModels.of("example.com", findings);

        assertEquals(4, model.nodes().size());
        for (GraphNode node : model.nodes().subList(1, 4)) {
            assertEquals(GraphNodeKind.LEAF, node.kind());
            assertEquals("target", node.parentId());
            assertEquals("SUBDOMAIN", node.typeToken());
        }
    }

    // ---- 4. mixed ----

    @Test
    void mixedFindingsPutsHostChildrenBeforeLeafChildren() {
        List<FindingSnapshot> findings = List.of(
                port(1, "example.com", 443, "OPEN"),
                subdomain(2, "api.example.com"));
        GraphModel model = GraphModels.of("example.com", findings);

        // root, host, leaf, port -- depth-1 (host then leaf) before depth-2 (port)
        assertEquals(4, model.nodes().size());
        assertEquals(GraphNodeKind.TARGET, model.nodes().get(0).kind());
        assertEquals(GraphNodeKind.HOST, model.nodes().get(1).kind());
        assertEquals(GraphNodeKind.LEAF, model.nodes().get(2).kind());
        assertEquals(GraphNodeKind.PORT, model.nodes().get(3).kind());
    }

    // ---- 5. ordering, ports ----

    @Test
    void portsAreOrderedNumericallyNotLexicographically() {
        List<FindingSnapshot> findings = List.of(
                port(1, "example.com", 443, "OPEN"),
                port(2, "example.com", 22, "OPEN"),
                port(3, "example.com", 80, "OPEN"));
        GraphModel model = GraphModels.of("example.com", findings);

        List<Integer> portOrder = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.PORT)
                .map(GraphNode::port)
                .toList();
        assertEquals(List.of(22, 80, 443), portOrder);
    }

    // ---- 6. ordering, hosts and leaves; determinism ----

    @Test
    void hostsAndLeavesAreAlphabeticalAndByteIdenticalAcrossCalls() {
        List<FindingSnapshot> findings = List.of(
                port(1, "zeta.example.com", 80, "OPEN"),
                port(2, "alpha.example.com", 80, "OPEN"),
                subdomain(3, "zzz.example.com"),
                subdomain(4, "aaa.example.com"));

        GraphModel first = GraphModels.of("example.com", findings);
        GraphModel second = GraphModels.of("example.com", findings);
        assertEquals(first, second);

        List<String> hostSubjects = first.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.HOST)
                .map(GraphNode::label)
                .toList();
        assertEquals(List.of("alpha.example.com", "zeta.example.com"), hostSubjects);

        List<String> leafSubjects = first.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF)
                .map(GraphNode::label)
                .toList();
        assertEquals(List.of("aaa.example.com", "zzz.example.com"), leafSubjects);
    }

    // ---- 7. two distinct probe subjects ----

    @Test
    void twoDistinctProbeSubjectsProduceTwoHostNodesEachOwningItsOwnPorts() {
        List<FindingSnapshot> findings = List.of(
                port(1, "host1.example.com", 443, "OPEN"),
                port(2, "host2.example.com", 22, "OPEN"));
        GraphModel model = GraphModels.of("example.com", findings);

        List<GraphNode> hosts = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.HOST).toList();
        assertEquals(2, hosts.size());

        for (GraphNode host : hosts) {
            long ownedPorts = model.nodes().stream()
                    .filter(n -> n.kind() == GraphNodeKind.PORT && n.parentId().equals(host.id()))
                    .count();
            assertEquals(1, ownedPorts);
        }
    }

    // ---- 8. R5 verbatim host comparison ----

    @Test
    void hostSubjectComparisonIsVerbatimNotCaseFolded() {
        List<FindingSnapshot> findings = List.of(
                port(1, "Host", 443, "OPEN"),
                port(2, "host", 22, "OPEN"));
        GraphModel model = GraphModels.of("example.com", findings);

        long hostCount = model.nodes().stream().filter(n -> n.kind() == GraphNodeKind.HOST).count();
        // deliberate: "Host" and "host" are two distinct host strings, matching P2-08 R3 and the
        // DB's own verbatim identity index -- not a bug to "fix" into case-insensitive grouping.
        assertEquals(2, hostCount);
    }

    // ---- 9. R6 no target/host collapse ----

    @Test
    void probeSubjectEqualToTargetStillYieldsADistinctHostNode() {
        List<FindingSnapshot> findings = List.of(port(1, "example.com", 443, "OPEN"));
        GraphModel model = GraphModels.of("example.com", findings);

        GraphNode root = model.nodes().get(0);
        GraphNode host = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.HOST).findFirst().orElseThrow();
        GraphNode portNode = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.PORT).findFirst().orElseThrow();

        assertEquals(root.label(), host.label());
        assertTrue(host != root);
        assertEquals(root.id(), host.parentId());
        assertEquals(host.id(), portNode.parentId());
    }

    // ---- 10. malformed, half-null ----

    @Test
    void halfNullFindingsBecomeLeavesNeverExceptionsNeverDroppedRows() {
        List<FindingSnapshot> findings = List.of(
                new FindingSnapshot(1, "PORT", "example.com", null, "OPEN"),
                new FindingSnapshot(2, "PORT", "example.com", 443, null));
        GraphModel model = GraphModels.of("example.com", findings);

        assertEquals(3, model.nodes().size());
        assertEquals(2, model.totalLeafCount());
        List<GraphNode> leaves = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF).toList();
        assertEquals(2, leaves.size());
    }

    // ---- 11. unseen vocabulary ----

    @Test
    void unseenFindingTypeStillRendersAsItsOwnLeaf() {
        List<FindingSnapshot> findings = List.of(leaf(1, "CERTIFICATE", "example.com"));
        GraphModel model = GraphModels.of("example.com", findings);

        GraphNode certificate = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF).findFirst().orElseThrow();
        assertEquals("CERTIFICATE", certificate.typeToken());
        assertEquals("certificate · example.com", GraphModels.describe(model, certificate));
    }

    // ---- 12. duplicate names not deduped ----

    @Test
    void duplicateSubjectsAreNotDeduped() {
        List<FindingSnapshot> findings = List.of(
                subdomain(1, "dup.example.com"),
                subdomain(2, "dup.example.com"));
        GraphModel model = GraphModels.of("example.com", findings);

        List<GraphNode> leaves = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF).toList();
        assertEquals(2, leaves.size());
        assertTrue(!leaves.get(0).id().equals(leaves.get(1).id()));
    }

    // ---- 13. cap ----

    @Test
    void leavesAreCappedAtMaxLeafNodesKeepingTheAlphabeticalPrefix() {
        List<FindingSnapshot> findings = new ArrayList<>();
        // 300 leaves, subjects "sub000.example.com".."sub299.example.com" -- already alphabetical
        for (int i = 0; i < 300; i++) {
            findings.add(subdomain(i, String.format("sub%03d.example.com", i)));
        }
        GraphModel model = GraphModels.of("example.com", findings);

        assertEquals(250, model.renderedLeafCount());
        assertEquals(300, model.totalLeafCount());
        assertTrue(model.isTruncated());
        String note = model.truncationNote();
        assertTrue(note.contains("250"));
        assertTrue(note.contains("300"));

        List<String> leafSubjects = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF)
                .map(GraphNode::label)
                .toList();
        assertEquals(250, leafSubjects.size());
        assertEquals("sub000.example.com", leafSubjects.get(0));
        assertEquals("sub249.example.com", leafSubjects.get(249));
    }

    // ---- 14. malformed target ----

    @Test
    void malformedTargetOrFindingsThrow() {
        assertThrows(NullPointerException.class, () -> GraphModels.of(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> GraphModels.of("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> GraphModels.of("   ", List.of()));
        assertThrows(NullPointerException.class, () -> GraphModels.of("example.com", null));
    }

    // ---- 15. describe, exact strings ----

    @Test
    void describeProducesExactStringsForEachKind() {
        List<FindingSnapshot> findings = List.of(port(1, "example.com", 443, "OPEN"));
        GraphModel model = GraphModels.of("example.com", findings);

        GraphNode root = model.nodes().get(0);
        GraphNode host = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.HOST).findFirst().orElseThrow();
        GraphNode portNode = model.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.PORT).findFirst().orElseThrow();

        assertEquals("scan target · example.com", GraphModels.describe(model, root));
        assertEquals("host probed · example.com", GraphModels.describe(model, host));
        assertEquals("port 443 · state open · host example.com",
                GraphModels.describe(model, portNode));

        GraphModel leafModel = GraphModels.of("example.com",
                List.of(subdomain(1, "api.example.com")));
        GraphNode leafNode = leafModel.nodes().stream()
                .filter(n -> n.kind() == GraphNodeKind.LEAF).findFirst().orElseThrow();
        assertEquals("subdomain · api.example.com", GraphModels.describe(leafModel, leafNode));
    }
}
