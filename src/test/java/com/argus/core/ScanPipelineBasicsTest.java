package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Single-threaded behaviour of {@link ScanPipeline}: FIFO ordering, capacity bookkeeping,
 * {@code drainAvailable} semantics and argument validation. No thread is started anywhere in
 * this class (plan §5 checkpoint after task 7).
 */
class ScanPipelineBasicsTest {

    @Test
    void publishThenTakeReturnsTheItem() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        pipeline.publish("finding-1");

        assertEquals(Optional.of("finding-1"), pipeline.take());
    }

    @Test
    void fifoOrderIsPreservedForOneProducer() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>();

        for (int i = 1; i <= 5; i++) {
            pipeline.publish(i);
        }

        for (int i = 1; i <= 5; i++) {
            assertEquals(Optional.of(i), pipeline.take());
        }
    }

    @Test
    void sizeReflectsQueuedItemsAndNeverExceedsCapacity() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>(8);

        pipeline.publish("a");
        pipeline.publish("b");
        pipeline.publish("c");

        assertEquals(3, pipeline.size());
        assertEquals(8, pipeline.capacity());
    }

    @Test
    void publishRejectsNullItem() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        assertThrows(NullPointerException.class, () -> pipeline.publish(null));
        assertEquals(0, pipeline.size());
    }

    @Test
    void constructorRejectsCapacityBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ScanPipeline<String>(0));
        assertThrows(IllegalArgumentException.class, () -> new ScanPipeline<String>(-1));
    }

    @Test
    void noArgConstructorUsesDefaultCapacity() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        assertEquals(ScanPipeline.DEFAULT_CAPACITY, pipeline.capacity());
        assertTrue(ScanPipeline.DEFAULT_CAPACITY >= 1);
    }

    @Test
    void drainAvailableReturnsUpToMaxItemsInFifoOrder() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>();
        for (int i = 1; i <= 5; i++) {
            pipeline.publish(i);
        }

        List<Integer> drained = pipeline.drainAvailable(3);

        assertEquals(List.of(1, 2, 3), drained);
        assertEquals(2, pipeline.size());
    }

    @Test
    void drainAvailableOnEmptyOpenPipelineReturnsEmptyListAndDoesNotBlock() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        List<String> drained = pipeline.drainAvailable(10);

        assertEquals(List.of(), drained);
        assertFalse(pipeline.isClosed());
    }

    @Test
    void drainAvailableReturnedListIsUnmodifiable() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        pipeline.publish("a");

        List<String> drained = pipeline.drainAvailable(10);

        assertThrows(UnsupportedOperationException.class, () -> drained.add("b"));
    }

    @Test
    void drainAvailableRejectsMaxItemsBelowOne() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        assertThrows(IllegalArgumentException.class, () -> pipeline.drainAvailable(0));
        assertThrows(IllegalArgumentException.class, () -> pipeline.drainAvailable(-1));
    }

    @Test
    void countersConserve() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>();

        for (int i = 1; i <= 5; i++) {
            pipeline.publish(i);
        }
        pipeline.take();
        pipeline.drainAvailable(2);

        assertEquals(pipeline.publishedCount(), pipeline.consumedCount() + pipeline.size());
    }

    @Test
    void isDrainedIsFalseWhileOpenEvenWhenEmpty() {
        ScanPipeline<String> pipeline = new ScanPipeline<>();

        assertFalse(pipeline.isDrained());
    }
}
