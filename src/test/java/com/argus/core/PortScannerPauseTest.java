package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-P1..T-P7 (§6.3 of the plan): the streaming submit loop's pause gate. Determinism technique
 * throughout — gate the first {@code window} ports on {@link FakeSocketConnector}'s
 * {@code releaseLatch}, {@code pause()} once {@code startedLatch} fires, release them, then
 * spin on {@code isAwaitingResumeForTest()} with zero {@code Thread.sleep} (§6 standing rules).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerPauseTest {

    private static void spinUntilAwaitingResume(PortScanner scanner) {
        while (!scanner.isAwaitingResumeForTest()) {
            Thread.onSpinWait();
        }
    }

    private static Thread startStreamingScanOnItsOwnThread(PortScanner scanner, ScanSink<Object> sink,
            AtomicReference<Throwable> failure) {
        Thread scanThread = new Thread(() -> {
            try {
                scanner.scan(sink);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        scanThread.start();
        return scanThread;
    }

    @Test
    void pausedMidScanMeansNoFurtherPortIsProbed() throws Exception {
        List<Integer> ports = PortSpec.range(1, 20);
        CountDownLatch startedLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(1);
        connector.blockOn(2);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "workers never started");
        scanner.pause();
        releaseLatch.countDown();
        spinUntilAwaitingResume(scanner);

        assertEquals(2, connector.probedPorts().size());
        // window == the 2 gated ports: the loop harvests+publishes exactly ONE completed
        // result, then immediately tries to top the window back up to keep 2 outstanding
        // (§4.4) — THAT resubmit attempt is what parks in the pause gate, one publish shy of
        // both. The second already-completed result stays queued, unpublished, until resume();
        // see resultsCompletedBeforePauseAreStillPublished for the "not lost" half of this.
        assertEquals(1, sink.published().size());

        scanner.resume();
        scanThread.join(15_000);
        assertFalse(scanThread.isAlive());
        assertEquals(null, failure.get());
    }

    @Test
    void resumeCompletesTheScan() throws Exception {
        List<Integer> ports = PortSpec.range(1, 20);
        CountDownLatch startedLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(1);
        connector.blockOn(2);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        scanner.pause();
        releaseLatch.countDown();
        spinUntilAwaitingResume(scanner);

        scanner.resume();
        scanThread.join(15_000);

        assertFalse(scanThread.isAlive());
        assertEquals(null, failure.get());
        assertFalse(scanner.isPaused());
        assertEquals(20, sink.published().size());
        Set<Integer> streamedPorts = sink.published().stream()
                .map(PortResult.class::cast)
                .map(PortResult::port)
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(ports), streamedPorts);
    }

    @Test
    void resultsCompletedBeforePauseAreStillPublished() throws Exception {
        List<Integer> ports = PortSpec.range(1, 20);
        CountDownLatch startedLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(1);
        connector.blockOn(2);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        scanner.pause();
        releaseLatch.countDown();
        spinUntilAwaitingResume(scanner);

        // Exactly one of the two gated ports' results is published by the time the loop parks
        // (see pausedMidScanMeansNoFurtherPortIsProbed for why it is one, not both); the point
        // of this test is that it is published at all, not lost, i.e. pause is not a
        // publication mute.
        Set<Integer> publishedPorts = sink.published().stream()
                .map(PortResult.class::cast)
                .map(PortResult::port)
                .collect(Collectors.toSet());
        assertEquals(1, publishedPorts.size(), "pause is not a publication mute");
        assertTrue(Set.of(1, 2).containsAll(publishedPorts),
                "the published port must be one of the two gated ports");

        scanner.resume();
        scanThread.join(15_000);
        assertFalse(scanThread.isAlive());
        assertEquals(null, failure.get());
    }

    @Test
    void pauseBeforeStreamingScanStartsParksAtTheFirstPort() throws Exception {
        List<Integer> ports = List.of(80, 81, 82);
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.pause();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        spinUntilAwaitingResume(scanner);
        assertTrue(connector.probedPorts().isEmpty());

        scanner.resume();
        scanThread.join(15_000);
        assertFalse(scanThread.isAlive());
        assertEquals(null, failure.get());
        assertEquals(3, sink.published().size());
    }

    @Test
    void spuriousWakeupWhileParkedChangesNothingBecauseTheGuardIsAWhile() throws Exception {
        List<Integer> ports = PortSpec.range(1, 10);
        CountDownLatch startedLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(1);
        connector.blockOn(2);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        scanner.pause();
        releaseLatch.countDown();
        spinUntilAwaitingResume(scanner);

        scanner.spuriousWakeupForTest();
        // No sleep: if the guard were `if` not `while`, the loop would have raced ahead and
        // the next assertion would be flaky/false. A brief spin re-observation still holds.
        assertTrue(scanner.isAwaitingResumeForTest());
        assertEquals(2, connector.probedPorts().size());

        scanner.resume();
        scanThread.join(15_000);
        assertFalse(scanThread.isAlive());
        assertEquals(null, failure.get());
        assertEquals(10, sink.published().size());
    }

    @Test
    void batchScanIgnoresPause() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.pause();
        List<PortResult> results = scanner.scan();

        assertEquals(3, results.size(), "pause must not gate the batch overload");
        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void pauseAndResumeAreIdempotentAndSafeFromMultipleThreads() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.pause();
        scanner.pause();
        assertTrue(scanner.isPaused());

        int threadCount = 3;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(() -> assertDoesNotThrow(() -> {
                ready.countDown();
                go.await();
                scanner.pause();
                scanner.resume();
            }));
            workers.add(worker);
            worker.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        scanner.resume();
        scanner.resume();
        assertFalse(scanner.isPaused());
    }
}
