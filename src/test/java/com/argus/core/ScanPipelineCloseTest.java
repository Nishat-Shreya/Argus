package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Single-threaded close/drain semantics: closing never discards, drains repeatedly return
 * empty rather than blocking, and publishing after close throws loudly instead of silently
 * dropping the finding (plan R6).
 */
class ScanPipelineCloseTest {

    @Test
    void closeIsIdempotent() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        pipeline.close();
        pipeline.close();
        pipeline.close();

        assertTrue(pipeline.isClosed());
    }

    @Test
    void closeDoesNotDiscardQueuedItems() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>();
        pipeline.publish(1);
        pipeline.publish(2);
        pipeline.publish(3);

        pipeline.close();

        assertEquals(Optional.of(1), pipeline.take());
        assertEquals(Optional.of(2), pipeline.take());
        assertEquals(Optional.of(3), pipeline.take());
        assertEquals(Optional.empty(), pipeline.take());
    }

    @Test
    void takeOnClosedEmptyPipelineReturnsEmptyRepeatedlyWithoutBlocking() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        pipeline.close();

        assertEquals(Optional.empty(), pipeline.take());
        assertEquals(Optional.empty(), pipeline.take());
        assertEquals(Optional.empty(), pipeline.take());
    }

    @Test
    void isDrainedTrueOnlyWhenClosedAndEmpty() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        assertFalse(pipeline.isDrained());

        pipeline.publish("a");
        pipeline.close();
        assertFalse(pipeline.isDrained());

        pipeline.take();
        assertTrue(pipeline.isDrained());
    }

    @Test
    void publishAfterCloseThrowsIllegalStateException() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        pipeline.close();

        assertThrows(IllegalStateException.class, () -> pipeline.publish("late"));
        assertEquals(0, pipeline.size());
        assertEquals(0, pipeline.publishedCount());
    }

    @Test
    void drainAvailableAfterCloseStillYieldsQueuedItems() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>();
        pipeline.publish(1);
        pipeline.publish(2);

        pipeline.close();

        List<Integer> drained = pipeline.drainAvailable(10);
        assertEquals(List.of(1, 2), drained);
    }
}
