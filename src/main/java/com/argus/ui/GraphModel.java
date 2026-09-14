package com.argus.ui;

import java.util.List;
import java.util.Objects;

/**
 * The whole rendered tree (plan §3.3). {@code nodes} is ordered: root first, then depth-1
 * children in model order (hosts then leaves), then depth-2 (ports) -- ordering is part of the
 * contract, it is what makes {@link GraphLayout#radial} byte-for-byte reproducible.
 */
record GraphModel(List<GraphNode> nodes, String target, int renderedLeafCount,
        int totalLeafCount) {

    GraphModel {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(target, "target");
        nodes = List.copyOf(nodes);
    }

    /** True when the leaf side was capped by {@link GraphModels#MAX_LEAF_NODES}. */
    boolean isTruncated() {
        return totalLeafCount > renderedLeafCount;
    }

    /** {@code ""} when not truncated, else the exact on-screen sentence (plan §3.4). */
    String truncationNote() {
        if (!isTruncated()) {
            return "";
        }
        return "showing " + renderedLeafCount + " of " + totalLeafCount
                + " — the graph caps leaf nodes at " + GraphModels.MAX_LEAF_NODES
                + " to stay readable";
    }
}
