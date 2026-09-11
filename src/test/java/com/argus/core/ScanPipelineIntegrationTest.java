package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Producers = scan workers: {@link PortScanner} and {@link SubdomainEnumerator}, wrapped by
 * {@link ScanProducerTask}, feed one {@link ScanPipeline} that a single consumer thread
 * drains — with zero edits to either scanner and reusing only the existing test doubles
 * ({@link FakeSocketConnector}, {@link FakeHttpFetcher}, {@link RecordingThreadFactory}).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanPipelineIntegrationTest {

    private static final List<Subdomain> GOLDEN_LIST = List.of(
            new Subdomain("*.example.com"),
            new Subdomain("api.example.com"),
            new Subdomain("assets.example.com"),
            new Subdomain("dev.example.com"),
            new Subdomain("example.com"),
            new Subdomain("legacy.example.com"),
            new Subdomain("mail.example.com"),
            new Subdomain("www.example.com"));

    private static final Set<PortResult> EXPECTED_PORT_RESULTS = Set.of(
            new PortResult("127.0.0.1", 80, PortState.OPEN),
            new PortResult("127.0.0.1", 81, PortState.OPEN),
            new PortResult("127.0.0.1", 82, PortState.OPEN));

    private static ScanProducerTask<PortResult> newPortsProducer(ScanPipeline<Object> pipeline) {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory scannerThreads =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82)).withThreadCount(2);
        PortScanner scanner = new PortScanner(request, connector, scannerThreads);
        return new ScanProducerTask<>(scanner::scan, pipeline);
    }

    private static ScanProducerTask<Subdomain> newSubdomainsProducer(ScanPipeline<Object> pipeline)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("example-com.json")));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fetcher);
        return new ScanProducerTask<>(() -> enumerator.enumerate("example.com"), pipeline);
    }

    private static Thread startConsumer(ScanPipeline<Object> pipeline, List<Object> collected) {
        Thread consumer = new Thread(() -> {
            try {
                Optional<Object> item;
                while ((item = pipeline.take()).isPresent()) {
                    collected.add(item.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pipeline-test-consumer");
        consumer.start();
        return consumer;
    }

    private static void assertNoLosses(List<Object> collected) {
        Set<PortResult> collectedPorts = collected.stream()
                .filter(PortResult.class::isInstance)
                .map(PortResult.class::cast)
                .collect(Collectors.toSet());
        Set<Subdomain> collectedSubdomains = collected.stream()
                .filter(Subdomain.class::isInstance)
                .map(Subdomain.class::cast)
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_PORT_RESULTS, collectedPorts);
        assertEquals(new HashSet<>(GOLDEN_LIST), collectedSubdomains);
        assertEquals(EXPECTED_PORT_RESULTS.size() + GOLDEN_LIST.size(), collected.size(),
                "expected no duplicates and no losses across the union");
    }

    @Test
    void portScannerAndSubdomainEnumeratorFeedOneConsumerThroughOnePipeline() throws Exception {
        ScanPipeline<Object> pipeline = new ScanPipeline<>();
        ScanProducerTask<PortResult> portsProducer = newPortsProducer(pipeline);
        ScanProducerTask<Subdomain> subsProducer = newSubdomainsProducer(pipeline);

        List<Object> collected = new CopyOnWriteArrayList<>();
        Thread consumer = startConsumer(pipeline, collected);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> portsCount = pool.submit(portsProducer);
            Future<Integer> subsCount = pool.submit(subsProducer);

            assertEquals(3, portsCount.get(10, TimeUnit.SECONDS));
            assertEquals(8, subsCount.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        pipeline.close();
        consumer.join(10_000);
        assertFalse(consumer.isAlive());

        assertNoLosses(collected);
    }

    @Test
    void coordinatorShutdownSequenceTerminatesEverything() throws Exception {
        ScanPipeline<Object> pipeline = new ScanPipeline<>();
        ScanProducerTask<PortResult> portsProducer = newPortsProducer(pipeline);
        ScanProducerTask<Subdomain> subsProducer = newSubdomainsProducer(pipeline);

        List<Object> collected = new CopyOnWriteArrayList<>();
        Thread consumer = startConsumer(pipeline, collected);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> portsCount = pool.submit(portsProducer);
        Future<Integer> subsCount = pool.submit(subsProducer);
        assertEquals(3, portsCount.get(10, TimeUnit.SECONDS));
        assertEquals(8, subsCount.get(10, TimeUnit.SECONDS));

        // §4.6 shutdown ordering: pool down first, pipeline closed only after every producer
        // has terminated, then the consumer drains the remainder and its loop exits.
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        pipeline.close();
        consumer.join(10_000);

        assertTrue(pool.isTerminated());
        assertTrue(pipeline.isDrained());
        assertFalse(consumer.isAlive());
        assertNoLosses(collected);
    }
}
