package com.argus.ui;

import java.util.Objects;

/**
 * One vertex of the rendered graph (plan §3.2). {@code parentId} is the ONLY relationship a node
 * carries -- there is no edge type. An edge in the rendered graph means "discovered under this
 * scan target," never "resolves to" (plan §0.2 rule 1).
 *
 * <p>{@code id} is unique by construction: {@code "target"} for the root, {@code "host:" +
 * subject} for hosts, {@code "finding:" + snapshot.id()} for every finding-derived node (the
 * persisted row id -- unique by definition, so two identically-named subdomain rows are two
 * distinct nodes with no dedup logic).
 */
record GraphNode(String id, String parentId, GraphNodeKind kind, String label,
        String typeToken, Integer port, String state) {

    GraphNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        if (kind == GraphNodeKind.TARGET) {
            if (parentId != null) {
                throw new IllegalArgumentException("a TARGET node must have a null parentId");
            }
        } else if (parentId == null) {
            throw new IllegalArgumentException(
                    "a non-TARGET node must have a non-null parentId, kind=" + kind);
        }
    }
}
