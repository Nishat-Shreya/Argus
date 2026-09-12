package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Happy path and contract for the new streaming overload {@code scan(ScanSink)} (T-S1..T-S8,
 * §6.1 of the plan). No gating here — {@link PortScannerPauseTest} and
 * {@link PortScannerStreamingCancellationTest} cover the concurrency-sensitive paths.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerStreamingTest {

    @Test
    void everyPortIsPublishedExactlyOnce() throws Exception {
        List<Integer> ports = PortSpec.range(80, 87);
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);
        PortScanner scanner = new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.scan(sink);

        assertEquals(8, sink.published().size());
        Set<PortResult> expected = ports.stream()
                .map(port -> new PortResult("127.0.0.1", port, PortState.OPEN))
                .collect(Collectors.toSet());
        Set<PortResult> actual = sink.published().stream()
                .map(PortResult.class::cast)
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void publicationOrderIsNotAscendingByContractOnlyTheSetIsAsserted() throws Exception {
        // Deliberately no order assertion: completion order is unspecified (§4.4/§8). Do not
        // add a List-equality assertion here — that would pin an implementation detail the
        // contract explicitly does not promise.
        List<Integer> ports = List.of(443, 22, 80, 21, 8080, 8443);
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(22, new java.net.ConnectException("refused"));
        connector.failWith(443, new java.net.SocketTimeoutException("timed out"));
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(3);
        PortScanner scanner = new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.scan(sink);

        Set<PortResult> expected = Set.of(
                new PortResult("127.0.0.1", 21, PortState.OPEN),
                new PortResult("127.0.0.1", 22, PortState.CLOSED),
                new PortResult("127.0.0.1", 80, PortState.OPEN),
                new PortResult("127.0.0.1", 443, PortState.FILTERED),
                new PortResult("127.0.0.1", 8080, PortState.OPEN),
                new PortResult("127.0.0.1", 8443, PortState.OPEN));
        Set<PortResult> actual = sink.published().stream()
                .map(PortResult.class::cast)
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void nullSinkThrowsNullPointerExceptionBeforeAnyThreadIsCreated() {
        FakeSocketConnector connector = new FakeSocketConnector();
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, recordingFactory);

        assertThrows(NullPointerException.class, () -> scanner.scan((ScanSink<PortResult>) null));

        assertTrue(recordingFactory.createdThreads().isEmpty());
        assertTrue(connector.probedPorts().isEmpty());
    }

    @Test
    void unknownHostThrowsUnknownHostExceptionBeforeAnyProbe() {
        RecordingThreadFactory recordingFactory =
                new RecordingThreadFactory(PortScanner.defaultThreadFactory());
        ScanRequest request = ScanRequest.of("256.256.256.256", List.of(80));
        PortScanner scanner =
                new PortScanner(request, new FakeSocketConnector(), recordingFactory);
        RecordingScanSink sink = new RecordingScanSink();

        assertThrows(UnknownHostException.class, () -> scanner.scan(sink));

        assertTrue(scanner.isPoolTerminated());
        assertTrue(recordingFactory.createdThreads().isEmpty());
    }

    @Test
    void poolIsTerminatedAfterNormalStreamingScan() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.scan(sink);

        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void batchThenStreamingThrowsIllegalStateException() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.scan();

        assertThrows(IllegalStateException.class, () -> scanner.scan(new RecordingScanSink()));
    }

    @Test
    void streamingThenBatchThrowsIllegalStateException() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.scan(new RecordingScanSink());

        assertThrows(IllegalStateException.class, scanner::scan);
    }

    @Test
    void streamingTwiceThrowsIllegalStateException() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());

        scanner.scan(new RecordingScanSink());

        assertThrows(IllegalStateException.class, () -> scanner.scan(new RecordingScanSink()));
    }

    @Test
    void cancelBeforeStreamingScanMeansNothingIsProbedOrPublished() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80, 81, 82));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        scanner.cancel();
        scanner.scan(sink);

        assertTrue(sink.published().isEmpty());
        assertTrue(connector.probedPorts().isEmpty());
        assertTrue(scanner.isPoolTerminated());
    }

    @Test
    void unexpectedRuntimeExceptionPropagatesAndPoolIsTerminated() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new RuntimeException("boom"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner =
                new PortScanner(request, connector, PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();

        assertThrows(RuntimeException.class, () -> scanner.scan(sink));

        assertTrue(scanner.isPoolTerminated());
    }
}
