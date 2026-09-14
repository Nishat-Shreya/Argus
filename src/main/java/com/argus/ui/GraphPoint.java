package com.argus.ui;

/**
 * One laid-out position (plan §3.5). Normalized {@code x}/{@code y} in {@code [0,1]}. Geometry
 * is kept out of {@link GraphNode} on purpose: the model is testable without the layout and vice
 * versa.
 */
record GraphPoint(String nodeId, double x, double y, int depth, boolean labelled) { }
