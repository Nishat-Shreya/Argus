package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-I1..T-I3 (§6.5 of the plan): {@code scan(ScanSink)} against a real, bounded
 * {@link ScanPipeline} with a single consumer thread. Proves §4.4's "publish before refill"
 * rule does not deadlock against a bounded queue, and that results genuinely arrive while the
 * producer is still running — the property a batch {@code scan()} could never demonstrate.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerStreamingPipelineTest {

    private static Thread startConsumer(ScanPipeline<PortResult> pipeline,
            List<PortResult> collected) {
        Thread consumer = new Thread(() -> {
            try {
                Optional<PortResult> item;
                while ((item = pipeline.take()).isPresent()) {
                    collected.add(item.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "streaming-pipeline-test-consumer");
        consumer.start();
        return consumer;
    }

    @Test
    void consumerReceivesEveryResultExactlyOnceThroughABoundedPipeline() throws Exception {
        List<Integer> ports = PortSpec.range(1, 16);
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        ScanPipeline<PortResult> pipeline = new ScanPipeline<>(2); // capacity 2: guaranteed backpressure

        List<PortResult> collected = new CopyOnWriteArrayList<>();
        Thread consumer = startConsumer(pipeline, collected);

        Thread producer = new Thread(() -> {
            try {
                scanner.scan(pipeline);
            } catch (Exception e) {
                throw new AssertionError("scan(sink) threw unexpectedly", e);
            }
        }, "streaming-pipeline-test-producer");
        producer.start();
        producer.join(20_000);
        assertFalse(producer.isAlive(), "producer did not terminate");

        pipeline.close();
        consumer.join(10_000);
        assertFalse(consumer.isAlive(), "consumer did not terminate");

        Set<Integer> collectedPorts =
                collected.stream().map(PortResult::port).collect(Collectors.toSet());
        assertEquals(Set.copyOf(ports), collectedPorts);
        assertEquals(16, collected.size(), "expected no losses and no duplicates");
    }

    @Test
    void resultsArriveBeforeTheProducerFinishes() throws Exception {
        List<Integer> ports = PortSpec.range(1, 6);
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(6); // the last port gates; ports 1-5 complete instantly
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(6);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        ScanPipeline<PortResult> pipeline = new ScanPipeline<>();

        List<PortResult> collected = new CopyOnWriteArrayList<>();
        Thread consumer = startConsumer(pipeline, collected);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                scanner.scan(pipeline);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "streaming-pipeline-test-producer");
        producer.start();

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "gated worker never started");
        // The instant-completing ports (1-5) should reach the consumer while the producer is
        // still blocked on the gated port (6) — the property a batch scan() cannot show, since
        // it only publishes after every probe (including the gated one) has completed.
        while (collected.isEmpty()) {
            Thread.onSpinWait();
        }
        assertTrue(producer.isAlive(), "producer finished before the gated port was released");
        assertTrue(collected.size() >= 1);

        releaseLatch.countDown();
        producer.join(15_000);
        assertFalse(producer.isAlive());
        assertEquals(null, failure.get());

        pipeline.close();
        consumer.join(10_000);
        assertFalse(consumer.isAlive());

        Set<Integer> collectedPorts =
                collected.stream().map(PortResult::port).collect(Collectors.toSet());
        assertEquals(Set.copyOf(ports), collectedPorts);
    }

    @Test
    void closingThePipelineMidScanFailsWithIllegalStateExceptionAndPoolStillTerminates()
            throws Exception {
        List<Integer> ports = PortSpec.range(1, 20);
        CountDownLatch startedLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(1);
        connector.blockOn(2);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        ScanPipeline<PortResult> pipeline = new ScanPipeline<>();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                scanner.scan(pipeline);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "streaming-pipeline-test-producer");
        producer.start();

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        pipeline.close();
        releaseLatch.countDown();
        producer.join(15_000);

        assertFalse(producer.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(scanner.isPoolTerminated());
    }
}
