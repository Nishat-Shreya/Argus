package com.argus.core;

import static com.argus.core.ScanPipelineBlockingTest.awaitParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The no-lost/no-duplicated core of the item (architecture.md's "Concurrency contract"),
 * order-independent across producers. Capacity is deliberately small (4 producers x 250 items
 * into a capacity-4 pipeline) so producers genuinely park on full and the not-full path is
 * exercised (plan §6.5) — nothing here asserts interleaving, ordering across producers, or
 * elapsed time.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanPipelineConcurrencyTest {

    private static final int PRODUCERS = 4;
    private static final int ITEMS_PER_PRODUCER = 250;
    private static final int CAPACITY = 4;

    private static Set<Integer> expectedItems() {
        Set<Integer> expected = new HashSet<>();
        for (int p = 0; p < PRODUCERS; p++) {
            for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                expected.add(p * 1_000 + i);
            }
        }
        return expected;
    }

    private static List<Thread> startProducers(ScanPipeline<Integer> pipeline) {
        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            int producerIndex = p;
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        pipeline.publish(producerIndex * 1_000 + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "pipeline-test-producer-" + producerIndex);
            producers.add(producer);
            producer.start();
        }
        return producers;
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(15_000);
            assertFalse(thread.isAlive(), thread.getName() + " did not terminate");
        }
    }

    @Test
    void fourProducersAndOneConsumerLoseNoItemsAndDuplicateNone() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(CAPACITY);
        List<Thread> producers = startProducers(pipeline);

        List<Integer> collected = new CopyOnWriteArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                Optional<Integer> item;
                while ((item = pipeline.take()).isPresent()) {
                    collected.add(item.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-test-consumer");
        consumer.start();

        joinAll(producers);
        pipeline.close();
        consumer.join(15_000);
        assertFalse(consumer.isAlive());

        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, collected.size());
        Set<Integer> distinct = new HashSet<>(collected);
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, distinct.size());
        assertEquals(expectedItems(), distinct);
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, pipeline.publishedCount());
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, pipeline.consumedCount());
        assertEquals(0, pipeline.size());
        assertTrue(pipeline.isDrained());
    }

    @Test
    void theBatchingConsumerPatternLosesNothingEither() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(CAPACITY);
        List<Thread> producers = startProducers(pipeline);

        List<Integer> collected = new CopyOnWriteArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    Optional<Integer> first = pipeline.take();
                    if (first.isEmpty()) {
                        break;
                    }
                    collected.add(first.get());
                    collected.addAll(pipeline.drainAvailable(64));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-test-consumer");
        consumer.start();

        joinAll(producers);
        pipeline.close();
        consumer.join(15_000);
        assertFalse(consumer.isAlive());

        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, collected.size());
        Set<Integer> distinct = new HashSet<>(collected);
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, distinct.size());
        assertEquals(expectedItems(), distinct);
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, pipeline.publishedCount());
        assertEquals(PRODUCERS * ITEMS_PER_PRODUCER, pipeline.consumedCount());
        assertEquals(0, pipeline.size());
        assertTrue(pipeline.isDrained());
    }

    @Test
    void capacityIsNeverExceededUnderContention() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(CAPACITY);
        List<Thread> producers = startProducers(pipeline);

        List<Integer> observedSizes = new CopyOnWriteArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                Optional<Integer> item;
                while ((item = pipeline.take()).isPresent()) {
                    observedSizes.add(pipeline.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-test-consumer");
        consumer.start();

        joinAll(producers);
        pipeline.close();
        consumer.join(15_000);
        assertFalse(consumer.isAlive());

        for (int observedSize : observedSizes) {
            assertTrue(observedSize <= pipeline.capacity(),
                    "size() " + observedSize + " exceeded capacity " + pipeline.capacity());
        }
    }

    @Test
    void twoConsumersShareTheStreamWithoutDuplication() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(CAPACITY);
        List<Thread> producers = startProducers(pipeline);

        List<Integer> collectedA = new CopyOnWriteArrayList<>();
        List<Integer> collectedB = new CopyOnWriteArrayList<>();
        Thread consumerA = new Thread(() -> drainInto(pipeline, collectedA), "pipeline-test-consumer-a");
        Thread consumerB = new Thread(() -> drainInto(pipeline, collectedB), "pipeline-test-consumer-b");
        consumerA.start();
        consumerB.start();

        joinAll(producers);
        pipeline.close();
        consumerA.join(15_000);
        consumerB.join(15_000);
        assertFalse(consumerA.isAlive());
        assertFalse(consumerB.isAlive());

        Set<Integer> union = new HashSet<>(collectedA);
        union.addAll(collectedB);
        assertEquals(expectedItems(), union);
        assertEquals(collectedA.size() + collectedB.size(), union.size(),
                "an item was consumed by both consumers");

        Set<Integer> intersection = new HashSet<>(collectedA);
        intersection.retainAll(collectedB);
        assertTrue(intersection.isEmpty());
    }

    private static void drainInto(ScanPipeline<Integer> pipeline, List<Integer> sink) {
        try {
            Optional<Integer> item;
            while ((item = pipeline.take()).isPresent()) {
                sink.add(item.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void cleanShutdownLeavesNoThreadAliveAndNoItemQueued() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(CAPACITY);
        List<Thread> producers = startProducers(pipeline);

        List<Integer> collected = new CopyOnWriteArrayList<>();
        Thread consumer = new Thread(() -> drainInto(pipeline, collected), "pipeline-test-consumer");
        consumer.start();

        joinAll(producers);
        pipeline.close();
        consumer.join(15_000);

        assertFalse(consumer.isAlive());
        for (Thread producer : producers) {
            assertFalse(producer.isAlive());
        }
        assertTrue(pipeline.isDrained());
        assertEquals(0, pipeline.size());
    }

    @Test
    void closeReleasesAllProducersWhenNoConsumerEverRuns() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(1);
        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            int producerIndex = p;
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        pipeline.publish(producerIndex * 1_000 + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    // expected once close() runs while this producer is parked on full
                }
            }, "pipeline-test-producer-" + producerIndex);
            producers.add(producer);
            producer.start();
        }

        // At capacity 1 with no consumer, every producer parks after its first publish.
        for (Thread producer : producers) {
            awaitParked(producer);
        }

        pipeline.close();

        for (Thread producer : producers) {
            producer.join(15_000);
            assertFalse(producer.isAlive(), producer.getName() + " never terminated");
        }
    }
}
