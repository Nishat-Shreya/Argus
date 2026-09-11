package com.argus.core;

import static com.argus.core.ScanPipelineBlockingTest.awaitParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The invariant-4 proof: a parked thread's {@code while} guard re-tests its whole condition
 * and is not fooled by a notification meant for the other side (or by a genuine spurious
 * wakeup). Uses the package-private {@link ScanPipeline#spuriousWakeupForTest()} seam (§3.3,
 * R2) to inject a notifyAll() with no state change. With an {@code if} instead of a
 * {@code while}, the first spurious wakeup below would make the parked thread fall through
 * with its condition still false.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanPipelineGuardedWaitTest {

    @Test
    void spuriousWakeupsDoNotMakeAParkedConsumerReturn() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        AtomicReference<Optional<String>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                result.set(pipeline.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        consumer.start();

        awaitParked(consumer);
        for (int i = 0; i < 100; i++) {
            pipeline.spuriousWakeupForTest();
            awaitParked(consumer);
            assertEquals(1, done.getCount(), "consumer must not have returned yet");
        }

        pipeline.publish("X");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        consumer.join(10_000);

        assertEquals(Optional.of("X"), result.get());
        assertFalse(consumer.isAlive());
    }

    @Test
    void spuriousWakeupsDoNotLetAParkedProducerExceedCapacity() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(1);
        pipeline.publish(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                pipeline.publish(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        producer.start();

        awaitParked(producer);
        for (int i = 0; i < 100; i++) {
            pipeline.spuriousWakeupForTest();
            awaitParked(producer);
            assertEquals(1, pipeline.size());
            assertEquals(1, done.getCount(), "producer must still be parked");
        }

        assertEquals(Optional.of(1), pipeline.take());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        producer.join(10_000);

        assertFalse(producer.isAlive());
        List<Integer> remainder = pipeline.drainAvailable(10);
        assertEquals(List.of(2), remainder);
    }

    @Test
    void spuriousWakeupsDoNotCorruptTheEndOfStreamSignal() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        AtomicReference<Optional<String>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                result.set(pipeline.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        consumer.start();

        awaitParked(consumer);
        for (int i = 0; i < 100; i++) {
            pipeline.spuriousWakeupForTest();
            awaitParked(consumer);
            assertEquals(1, done.getCount());
        }

        pipeline.close();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        consumer.join(10_000);

        assertEquals(Optional.empty(), result.get());
        assertFalse(consumer.isAlive());
        assertTrue(pipeline.isDrained());
    }
}
