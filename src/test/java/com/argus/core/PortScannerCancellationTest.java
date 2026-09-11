package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cancellation is latch-gated and fully deterministic — no {@code Thread.sleep}, no wall-clock
 * assertions used as a correctness proxy (§6.6 of the plan). {@code cancel()} is callable from
 * any thread, is idempotent, and makes {@code scan()} return only the ports that completed —
 * the §4.2 check-then-act property under {@code lock}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerCancellationTest {

    @Test
    void cancelUnblocksScanAndTerminatesPool() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        connector.blockOn(80);
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        Thread scanThread = startScanOnItsOwnThread(scanner, new AtomicReference<>());

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "worker never started");
        scanner.cancel();
        releaseLatch.countDown();

        scanThread.join(15_000);
        assertFalse(scanThread.isAlive(), "scan thread did not finish");
        assertTrue(scanner.isCancelled());
        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void cancelReturnsOnlyCompletedPortsAsPartialResults() throws Exception {
        List<Integer> ports = PortSpec.range(1, 5);
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        for (int port : ports) {
            connector.blockOn(port);
        }
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        AtomicReference<List<PortResult>> resultHolder = new AtomicReference<>();
        Thread scanThread = startScanOnItsOwnThread(scanner, resultHolder);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "worker never started");
        scanner.cancel();
        releaseLatch.countDown();
        scanThread.join(15_000);

        List<PortResult> results = resultHolder.get();
        assertTrue(results.size() < ports.size(),
                "expected fewer than " + ports.size() + " results, got " + results.size());
        for (PortResult result : results) {
            assertTrue(ports.contains(result.port()), "unexpected port in results: " + result);
        }
    }

    @Test
    void cancelPreventsNotYetStartedPortsFromBeingProbed() throws Exception {
        List<Integer> ports = PortSpec.range(1, 50);
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        for (int port : ports) {
            connector.blockOn(port);
        }
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        Thread scanThread = startScanOnItsOwnThread(scanner, new AtomicReference<>());

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "worker never started");
        scanner.cancel();
        int probedAtCancelReturn = connector.probedPorts().size();
        releaseLatch.countDown();
        scanThread.join(15_000);

        assertEquals(probedAtCancelReturn, connector.probedPorts().size(),
                "no additional port should be probed after cancel() returned");
        assertTrue(connector.probedPorts().size() < 50,
                "expected far fewer than 50 ports probed, got "
                        + connector.probedPorts().size());
    }

    @Test
    void cancelIsIdempotentAndSafeFromMultipleThreads() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.cancel();

        int cancellerCount = 3;
        CountDownLatch ready = new CountDownLatch(cancellerCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> cancellers = new ArrayList<>();
        for (int i = 0; i < cancellerCount; i++) {
            Thread canceller = new Thread(() -> assertDoesNotThrow(() -> {
                ready.countDown();
                go.await();
                scanner.cancel();
            }));
            cancellers.add(canceller);
            canceller.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Thread canceller : cancellers) {
            canceller.join(10_000);
        }

        assertTrue(scanner.isCancelled());
    }

    @Test
    void cancelBeforeScanMeansNoPortIsEverProbed() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.cancel();
        List<PortResult> results = scanner.scan();

        assertTrue(results.isEmpty());
        assertTrue(connector.probedPorts().isEmpty());
        assertTrue(scanner.isPoolTerminated());
    }

    private static Thread startScanOnItsOwnThread(
            PortScanner scanner, AtomicReference<List<PortResult>> resultHolder) {
        Thread scanThread = new Thread(() -> {
            try {
                resultHolder.set(scanner.scan());
            } catch (Exception e) {
                throw new AssertionError("scan() threw unexpectedly", e);
            }
        });
        scanThread.start();
        return scanThread;
    }
}
