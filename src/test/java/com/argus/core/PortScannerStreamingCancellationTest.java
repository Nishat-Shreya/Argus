package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-C1..T-C5 (§6.4 of the plan): cancellation semantics are unchanged for the streaming
 * overload, and the §4.5 futures-before-shutdownNow ordering is pinned so a regression that
 * reorders {@code cancel()} hangs here (caught by {@code @Timeout}) instead of on production
 * traffic.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerStreamingCancellationTest {

    private static Thread startStreamingScanOnItsOwnThread(PortScanner scanner,
            ScanSink<Object> sink, AtomicReference<Throwable> failure) {
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
    void cancelDuringStreamingScanReturnsOnlyResultsForRequestedPorts() throws Exception {
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
        scanner.cancel();
        releaseLatch.countDown();
        scanThread.join(15_000);

        assertFalse(scanThread.isAlive());
        assertTrue(scanner.isCancelled());
        assertTrue(scanner.isPoolTerminated());
        assertEquals(null, failure.get());
        for (Object item : sink.published()) {
            PortResult result = (PortResult) item;
            assertTrue(ports.contains(result.port()), "unexpected port in results: " + result);
        }
    }

    @Test
    void cancelPreventsNotYetStartedPortsFromBeingProbed() throws Exception {
        List<Integer> ports = PortSpec.range(1, 50);
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
        scanner.cancel();
        int probedAtCancelReturn = connector.probedPorts().size();
        releaseLatch.countDown();
        scanThread.join(15_000);

        assertEquals(probedAtCancelReturn, connector.probedPorts().size(),
                "no additional port should be probed after cancel() returned");
        assertTrue(connector.probedPorts().size() < 50,
                "expected far fewer than 50 ports probed, got "
                        + connector.probedPorts().size());
        assertEquals(null, failure.get());
    }

    @Test
    void cancelWhileParkedInThePauseGateReleasesItWithoutResume() throws Exception {
        List<Integer> ports = PortSpec.range(1, 20);
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(2);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.pause();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        while (!scanner.isAwaitingResumeForTest()) {
            Thread.onSpinWait();
        }

        // This is the deadlock test for §4.2/§4.3: cancel() must release the gate on its own,
        // with no resume() call, or this join() never returns and @Timeout fails the test.
        scanner.cancel();
        scanThread.join(15_000);

        assertFalse(scanThread.isAlive(), "scan thread did not finish after cancel() while parked");
        assertTrue(scanner.isCancelled());
        assertTrue(scanner.isPoolTerminated());
        assertEquals(null, failure.get());
    }

    @Test
    void cancelBeforeShutdownNowOrderingMeansNoSubmittedTaskIsLostFromTheCompletionQueue()
            throws Exception {
        // §4.5's hard ordering pin (R5): every port gated on releaseLatch, released only after
        // cancel(). If cancel() ever reordered to shutdownNow() before cancelling futures, a
        // not-yet-started task's Future would be dropped from the pool queue uncancelled, its
        // done() would never run, it would never reach the completion queue, and cs.take()
        // would block forever — a hang caught only by @Timeout, not a clean assertion failure.
        List<Integer> ports = PortSpec.range(1, 10);
        CountDownLatch startedLatch = new CountDownLatch(4);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeSocketConnector connector = new FakeSocketConnector(startedLatch, releaseLatch);
        for (int port : ports) {
            connector.blockOn(port);
        }
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        scanner.cancel();
        releaseLatch.countDown();
        scanThread.join(15_000);

        assertFalse(scanThread.isAlive());
        assertTrue(scanner.isCancelled());
        assertTrue(scanner.isPoolTerminated());
        assertEquals(null, failure.get());
    }

    @Test
    void interruptingTheScanThreadSurfacesInterruptedExceptionAndTerminatesThePool()
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
        RecordingScanSink sink = new RecordingScanSink();

        // Pause first so the scan thread is parked in lock.wait() — a location that reliably
        // surfaces Thread.interrupt() as InterruptedException, unlike a blocking Socket.connect
        // (P1-01 §4.3), which is what this test needs to be deterministic rather than racy.
        scanner.pause();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scanThread = startStreamingScanOnItsOwnThread(scanner, sink, failure);

        while (!scanner.isAwaitingResumeForTest()) {
            Thread.onSpinWait();
        }

        scanThread.interrupt();
        scanThread.join(15_000);

        assertFalse(scanThread.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(scanner.isPoolTerminated());
    }
}
