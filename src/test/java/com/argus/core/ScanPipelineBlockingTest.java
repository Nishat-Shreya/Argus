package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Parked-thread behaviour of {@link ScanPipeline}, established deterministically with
 * {@link #awaitParked(Thread)} (a spin on {@link Thread#getState()}, not a sleep — see plan
 * §6.3) and {@link CountDownLatch} handshakes. Worker bodies here contain no other blocking
 * call, so {@code WAITING} is unambiguous evidence of parking inside {@code lock.wait()}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanPipelineBlockingTest {

    /** Spins on state, not time (plan §6.3); the class-level @Timeout is the hang detector. */
    static void awaitParked(Thread t) {
        while (t.getState() != Thread.State.WAITING) {
            assertTrue(t.isAlive(), "thread returned from wait() when it should have parked");
            Thread.onSpinWait();
        }
    }

    @Test
    void consumerParksOnEmptyPipelineAndWakesOnPublish() throws Exception {
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
        pipeline.publish("X");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        consumer.join(10_000);

        assertEquals(Optional.of("X"), result.get());
        assertFalse(consumer.isAlive());
    }

    @Test
    void closeUnblocksAParkedConsumerWithEndOfStream() throws Exception {
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
        pipeline.close();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        consumer.join(10_000);

        assertEquals(Optional.empty(), result.get());
        assertFalse(consumer.isAlive());
    }

    @Test
    void producerParksWhenCapacityIsReachedAndWakesOnTake() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(1);
        pipeline.publish(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                pipeline.publish(2);
            } catch (Exception e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        producer.start();

        awaitParked(producer);
        assertEquals(1, pipeline.size());

        assertEquals(Optional.of(1), pipeline.take());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        producer.join(10_000);

        assertFalse(producer.isAlive());
        assertNull(failure.get());
        assertEquals(Optional.of(2), pipeline.take());
    }

    @Test
    void closeUnblocksAParkedProducerWithIllegalStateException() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(1);
        pipeline.publish(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                pipeline.publish(2);
            } catch (Exception e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        producer.start();

        awaitParked(producer);
        pipeline.close();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        producer.join(10_000);

        assertFalse(producer.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException,
                "expected IllegalStateException, got " + failure.get());
    }

    @Test
    void interruptingAParkedConsumerThrowsInterruptedException() throws Exception {
        ScanPipeline<String> pipeline = new ScanPipeline<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                pipeline.take();
            } catch (InterruptedException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        consumer.start();

        awaitParked(consumer);
        consumer.interrupt();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        consumer.join(10_000);

        assertFalse(consumer.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        assertEquals(0, pipeline.consumedCount());

        pipeline.publish("still usable");
        assertEquals(Optional.of("still usable"), pipeline.take());
    }

    @Test
    void interruptingAParkedProducerDoesNotEnqueueTheItem() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(1);
        pipeline.publish(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                pipeline.publish(2);
            } catch (InterruptedException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        producer.start();

        awaitParked(producer);
        producer.interrupt();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        producer.join(10_000);

        assertFalse(producer.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        assertEquals(1, pipeline.size());
        assertEquals(1, pipeline.publishedCount());

        List<Integer> drained = pipeline.drainAvailable(10);
        assertEquals(List.of(1), drained);
        assertFalse(drained.contains(2));
    }

    @Test
    void itemsPublishedBeforeAnInterruptSurvive() throws Exception {
        ScanPipeline<Integer> pipeline = new ScanPipeline<>(2);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                pipeline.publish(1);
                pipeline.publish(2);
                pipeline.publish(3);
            } catch (InterruptedException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pipeline-test-1");
        producer.start();

        awaitParked(producer);
        producer.interrupt();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        producer.join(10_000);

        assertFalse(producer.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        List<Integer> drained = pipeline.drainAvailable(10);
        assertEquals(List.of(1, 2), drained);
    }
}
