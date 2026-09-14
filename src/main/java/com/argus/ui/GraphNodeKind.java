package com.argus.ui;

/**
 * A graph node's role in the rendered tree (plan §3.1) -- never its vocabulary. Derived purely
 * from nullness and position, never from a finding's {@code type()} literal.
 *
 * <ul>
 * <li>{@code TARGET} -- the single root, one per model, built from the scan's target string.</li>
 * <li>{@code HOST} -- one per distinct {@code subject} among probe findings
 *     ({@code port() != null && state() != null}).</li>
 * <li>{@code PORT} -- one per probe finding.</li>
 * <li>{@code LEAF} -- one per non-probe finding, attached directly to the root. Today always a
 *     subdomain; the constant is named for the graph role because the finding's own identity
 *     lives in {@link GraphNode#typeToken()}.</li>
 * </ul>
 */
enum GraphNodeKind {
    TARGET, HOST, PORT, LEAF
}
