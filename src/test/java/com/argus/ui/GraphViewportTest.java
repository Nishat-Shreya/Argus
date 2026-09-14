package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Section 7.3 of the plan: pan/zoom state as pure math, no JavaFX on the classpath. The pivot-
 * preservation algebra is the one thing in the interaction layer genuinely worth getting wrong,
 * so it is pure and unit-tested here rather than living in a mouse handler.
 */
class GraphViewportTest {

    @Test
    void identityIsScaleOneAndZeroTranslate() {
        GraphViewport viewport = GraphViewport.identity();
        assertEquals(1.0, viewport.scale(), 1e-9);
        assertEquals(0.0, viewport.translateX(), 1e-9);
        assertEquals(0.0, viewport.translateY(), 1e-9);
    }

    @Test
    void zoomingInRepeatedlyClampsAtMaxScale() {
        GraphViewport viewport = GraphViewport.identity();
        for (int i = 0; i < 100; i++) {
            viewport = viewport.zoomedAt(1.0, 400, 300);
        }
        assertEquals(GraphViewport.MAX_SCALE, viewport.scale(), 1e-9);
    }

    @Test
    void zoomingOutRepeatedlyClampsAtMinScale() {
        GraphViewport viewport = GraphViewport.identity();
        for (int i = 0; i < 100; i++) {
            viewport = viewport.zoomedAt(-1.0, 400, 300);
        }
        assertEquals(GraphViewport.MIN_SCALE, viewport.scale(), 1e-9);
    }

    @Test
    void zoomPreservesTheContentPointUnderThePivot() {
        GraphViewport viewport = GraphViewport.identity().pannedBy(50, -20);
        double pivotX = 130;
        double pivotY = 260;

        // content coordinate under the pivot before the zoom
        double contentXBefore = (pivotX - viewport.translateX()) / viewport.scale();
        double contentYBefore = (pivotY - viewport.translateY()) / viewport.scale();

        GraphViewport zoomed = viewport.zoomedAt(1.0, pivotX, pivotY);

        double contentXAfter = (pivotX - zoomed.translateX()) / zoomed.scale();
        double contentYAfter = (pivotY - zoomed.translateY()) / zoomed.scale();

        assertEquals(contentXBefore, contentXAfter, 1e-9);
        assertEquals(contentYBefore, contentYAfter, 1e-9);
    }

    @Test
    void zoomAboutTheOriginWithZeroTranslateIsPureScale() {
        GraphViewport viewport = GraphViewport.identity();
        GraphViewport zoomed = viewport.zoomedAt(1.0, 0, 0);

        assertEquals(GraphViewport.ZOOM_STEP, zoomed.scale(), 1e-9);
        assertEquals(0.0, zoomed.translateX(), 1e-9);
        assertEquals(0.0, zoomed.translateY(), 1e-9);
    }

    @Test
    void pannedByAccumulatesAndDoesNotTouchScale() {
        GraphViewport viewport = GraphViewport.identity().pannedBy(10, 20).pannedBy(-3, 5);

        assertEquals(1.0, viewport.scale(), 1e-9);
        assertEquals(7.0, viewport.translateX(), 1e-9);
        assertEquals(25.0, viewport.translateY(), 1e-9);
    }

    @Test
    void zoomInThenOutAboutTheSamePivotRestoresScaleAndTranslate() {
        GraphViewport original = GraphViewport.identity().pannedBy(40, -15);
        GraphViewport zoomed = original.zoomedAt(1.0, 200, 150);
        GraphViewport restored = zoomed.zoomedAt(-1.0, 200, 150);

        assertEquals(original.scale(), restored.scale(), 1e-9);
        assertEquals(original.translateX(), restored.translateX(), 1e-9);
        assertEquals(original.translateY(), restored.translateY(), 1e-9);
    }

    @Test
    void zeroNotchesProducesAnEqualViewport() {
        GraphViewport viewport = GraphViewport.identity().pannedBy(12, 34);
        GraphViewport zoomed = viewport.zoomedAt(0.0, 100, 100);

        assertEquals(viewport, zoomed);
    }

    @Test
    void scaleNeverExceedsBoundsAfterAnySingleZoom() {
        GraphViewport viewport = GraphViewport.identity();
        GraphViewport zoomed = viewport.zoomedAt(1.0, 0, 0);
        assertTrue(zoomed.scale() <= GraphViewport.MAX_SCALE);
        assertTrue(zoomed.scale() >= GraphViewport.MIN_SCALE);
    }
}
