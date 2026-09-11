package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pool cleanup (invariant 6): every exit path from {@code scan()} leaves the pool terminated,
 * worker threads are daemon and named {@code argus-scan-N}, thread count is capped at the
 * port count, and the scanner is single-use. Host is the literal {@code "127.0.0.1"}, never
 * {@code "localhost"} (R5).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerLifecycleTest {

    @Test
    void poolIsTerminatedAfterNormalScan() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.scan();

        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void allWorkerThreadsDieAfterScan() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82)).withThreadCount(3);
        PortScanner scanner = new PortScanner(request, connector, recordingFactory);

        scanner.scan();

        assertFalse(recordingFactory.createdThreads().isEmpty());
        for (Thread thread : recordingFactory.createdThreads()) {
            thread.join(5000);
            assertFalse(thread.isAlive(), thread.getName() + " is still alive after scan()");
        }
    }

    @Test
    void workerThreadsAreDaemonAndNamed() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81)).withThreadCount(2);
        PortScanner scanner = new PortScanner(request, connector, recordingFactory);

        scanner.scan();

        assertFalse(recordingFactory.createdThreads().isEmpty());
        for (Thread thread : recordingFactory.createdThreads()) {
            assertTrue(thread.isDaemon(), thread.getName() + " is not a daemon thread");
            assertTrue(thread.getName().matches("argus-scan-\\d+"),
                    "unexpected thread name: " + thread.getName());
        }
    }

    @Test
    void poolIsTerminatedWhenAProbeThrowsUnexpectedRuntimeException() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new RuntimeException("boom"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        try {
            scanner.scan();
        } catch (RuntimeException expected) {
            // propagation is an acceptable outcome (plan §6.5 T33)
        }

        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void poolIsTerminatedWhenHostIsUnknown() {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("256.256.256.256", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, recordingFactory);

        assertThrows(UnknownHostException.class, scanner::scan);

        assertTrue(scanner.isPoolTerminated());
        assertTrue(recordingFactory.createdThreads().isEmpty(),
                "no pool thread should ever be created when host resolution fails");
    }

    @Test
    void secondScanCallThrowsIllegalStateException() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.scan();

        assertThrows(IllegalStateException.class, scanner::scan);
    }

    @Test
    void threadCountIsCappedAtPortCount() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82)).withThreadCount(16);
        PortScanner scanner = new PortScanner(request, connector, recordingFactory);

        scanner.scan();

        assertTrue(recordingFactory.createdThreads().size() <= 3,
                "expected at most 3 threads, got " + recordingFactory.createdThreads().size());
    }
}
