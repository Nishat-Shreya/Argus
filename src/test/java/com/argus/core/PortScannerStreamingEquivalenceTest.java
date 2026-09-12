package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-E1..T-E4 (§6.2 of the plan): the backlog's headline assertion — for the same
 * {@link ScanRequest} and the same connector behaviour, the SET of streamed results equals
 * the SET of batch results. {@code PortScanner} is single-use, so each run gets its own
 * instance built from the same request.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerStreamingEquivalenceTest {

    private ServerSocket openServerSocket;

    @AfterEach
    void closeSockets() throws Exception {
        if (openServerSocket != null && !openServerSocket.isClosed()) {
            openServerSocket.close();
        }
    }

    private static FakeSocketConnector deterministicMixConnector() {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(81, new ConnectException("refused"));
        connector.failWith(82, new SocketTimeoutException("timed out"));
        connector.failWith(83, new NoRouteToHostException("no route"));
        return connector;
    }

    @Test
    void streamedAndBatchResultsAreSetEqualForADeterministicMix() throws Exception {
        List<Integer> ports = List.of(80, 81, 82, 83);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);

        PortScanner batchScanner = new PortScanner(
                request, deterministicMixConnector(), PortScanner.defaultThreadFactory());
        List<PortResult> batch = batchScanner.scan();

        PortScanner streamingScanner = new PortScanner(
                request, deterministicMixConnector(), PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();
        streamingScanner.scan(sink);
        List<PortResult> streamed = sink.published().stream()
                .map(PortResult.class::cast)
                .toList();

        assertEquals(Set.copyOf(batch), Set.copyOf(streamed));
        assertEquals(batch.size(), streamed.size(), "set equality alone would hide a duplicate");
    }

    @Test
    void streamedAndBatchResultsAreSetEqualOverALargerWindowedRequest() throws Exception {
        List<Integer> ports = PortSpec.range(1, 32);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);

        PortScanner batchScanner =
                new PortScanner(request, new FakeSocketConnector(), PortScanner.defaultThreadFactory());
        List<PortResult> batch = batchScanner.scan();

        PortScanner streamingScanner =
                new PortScanner(request, new FakeSocketConnector(), PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();
        streamingScanner.scan(sink);
        List<PortResult> streamed = sink.published().stream()
                .map(PortResult.class::cast)
                .toList();

        assertEquals(Set.copyOf(batch), Set.copyOf(streamed));
        assertEquals(batch.size(), streamed.size());
        assertEquals(32, streamed.size());
    }

    @Test
    void streamedAndBatchResultsAreSetEqualAgainstRealLoopbackSockets() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        openServerSocket = new ServerSocket(0, 50, loopback);
        ServerSocket closedServerSocket = new ServerSocket(0, 50, loopback);
        int openPort = openServerSocket.getLocalPort();
        int closedPort = closedServerSocket.getLocalPort();
        closedServerSocket.close();

        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(openPort, closedPort))
                .withThreadCount(2);

        PortScanner batchScanner = new PortScanner(request);
        List<PortResult> batch = batchScanner.scan();

        PortScanner streamingScanner = new PortScanner(request);
        RecordingScanSink sink = new RecordingScanSink();
        streamingScanner.scan(sink);
        List<PortResult> streamed = sink.published().stream()
                .map(PortResult.class::cast)
                .toList();

        assertEquals(Set.copyOf(batch), Set.copyOf(streamed));
        assertEquals(batch.size(), streamed.size());
    }

    @Test
    void everyPortIsStreamedExactlyOnceWhenWindowIsClampedByPortCount() throws Exception {
        List<Integer> ports = List.of(80, 81, 82);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(16);

        PortScanner streamingScanner =
                new PortScanner(request, new FakeSocketConnector(), PortScanner.defaultThreadFactory());
        RecordingScanSink sink = new RecordingScanSink();
        streamingScanner.scan(sink);

        Set<Integer> streamedPorts = sink.published().stream()
                .map(PortResult.class::cast)
                .map(PortResult::port)
                .collect(Collectors.toSet());

        assertEquals(Set.copyOf(ports), streamedPorts);
        assertEquals(3, sink.published().size());
    }
}
