package com.argus.core;

import static com.argus.core.ScanPipelineBlockingTest.awaitParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ScanProducerTask} adapts an existing batch-returning scan into a pipeline producer
 * with no change to the scanner's own API (plan §3.4). Uses {@link RecordingScanSink} for the
 * single-threaded cases and a real {@link ScanPipeline} for the parked-thread interrupt case
 * (T39), where genuine blocking behaviour is the point.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanProducerTaskTest {

    @Test
    void publishesEveryElementInIterationOrderAndReturnsTheCount() throws Exception {
        RecordingScanSink sink = new RecordingScanSink();
        Callable<List<String>> source = () -> List.of("a", "b", "c");
        ScanProducerTask<String> task = new ScanProducerTask<>(source, sink);

        Integer count = task.call();

        assertEquals(3, count);
        assertEquals(List.of("a", "b", "c"), sink.published());
    }

    @Test
    void doesNotClosePipeline() throws Exception {
        ScanPipeline<Object> pipeline = new ScanPipeline<>();
        Callable<List<String>> source = () -> List.of("a");
        ScanProducerTask<String> task = new ScanProducerTask<>(source, pipeline);

        task.call();

        assertFalse(pipeline.isClosed());
        assertFalse(pipeline.isDrained());
        pipeline.publish("still open");
    }

    @Test
    void emptyScanResultPublishesNothingAndReturnsZero() throws Exception {
        RecordingScanSink sink = new RecordingScanSink();
        Callable<List<String>> source = List::of;
        ScanProducerTask<String> task = new ScanProducerTask<>(source, sink);

        Integer count = task.call();

        assertEquals(0, count);
        assertTrue(sink.published().isEmpty());
    }

    @Test
    void propagatesTheScansOwnExceptionUnwrapped() {
        RecordingScanSink sink = new RecordingScanSink();
        SubdomainEnumerationException scriptedFailure =
                new SubdomainEnumerationException("crt.sh failed", 503);
        Callable<List<String>> source = () -> {
            throw scriptedFailure;
        };
        ScanProducerTask<String> task = new ScanProducerTask<>(source, sink);

        SubdomainEnumerationException thrown =
                assertThrows(SubdomainEnumerationException.class, task::call);

        assertSame(scriptedFailure, thrown);
        assertTrue(sink.published().isEmpty());
    }

    @Test
    void interruptDuringPublishPropagatesAndKeepsAlreadyPublishedItems() throws Exception {
        ScanPipeline<Object> pipeline = new ScanPipeline<>(1);
        Callable<List<String>> source = () -> List.of("first", "second", "third");
        ScanProducerTask<String> task = new ScanProducerTask<>(source, pipeline);

        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                task.call();
            } catch (Exception e) {
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
        assertTrue(failure.get() instanceof InterruptedException,
                "expected InterruptedException, got " + failure.get());
        assertEquals(Optional.of("first"), pipeline.take());
    }

    @Test
    void rejectsNullSourceOrSink() {
        RecordingScanSink sink = new RecordingScanSink();
        Callable<List<String>> source = List::of;

        assertThrows(NullPointerException.class, () -> new ScanProducerTask<>(null, sink));
        assertThrows(NullPointerException.class, () -> new ScanProducerTask<>(source, null));
    }
}
