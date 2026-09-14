package com.argus.ui;

/**
 * Pan/zoom state as pure math (plan §3.8). Immutable: every op returns a new instance. No
 * toolkit type appears in the signature -- pane coordinates are plain doubles.
 */
record GraphViewport(double scale, double translateX, double translateY) {

    static final double MIN_SCALE = 0.25;
    static final double MAX_SCALE = 4.0;
    static final double ZOOM_STEP = 1.1;

    static GraphViewport identity() {
        return new GraphViewport(1.0, 0.0, 0.0);
    }

    /**
     * Zooms by {@code ZOOM_STEP^notches}, clamped to {@code [MIN_SCALE, MAX_SCALE]}, while
     * preserving the content point under the pivot (in pane coordinates) -- the one formula in
     * the interaction layer genuinely worth getting wrong, so it lives here, pure and tested,
     * rather than in a mouse handler.
     */
    GraphViewport zoomedAt(double notches, double pivotX, double pivotY) {
        double rawScale = scale * Math.pow(ZOOM_STEP, notches);
        double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, rawScale));

        double contentX = (pivotX - translateX) / scale;
        double contentY = (pivotY - translateY) / scale;
        double newTranslateX = pivotX - contentX * newScale;
        double newTranslateY = pivotY - contentY * newScale;

        return new GraphViewport(newScale, newTranslateX, newTranslateY);
    }

    /** Pans by {@code (dx, dy)} pane pixels; accumulates, never touches {@code scale}. */
    GraphViewport pannedBy(double dx, double dy) {
        return new GraphViewport(scale, translateX + dx, translateY + dy);
    }
}
