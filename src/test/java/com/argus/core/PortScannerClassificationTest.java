package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Classifies a probe outcome into exactly one of {@link PortState#OPEN}/{@code CLOSED}/
 * {@code FILTERED} (§3.6 of the plan). Uses {@link FakeSocketConnector}: zero sockets, zero
 * off-box network. Host is the literal {@code "127.0.0.1"} so resolution never hits DNS (R5).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerClassificationTest {

    @Test
    void connectSuccessIsOpen() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(new PortResult("127.0.0.1", 80, PortState.OPEN)), results);
    }

    @Test
    void connectionRefusedIsClosed() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new ConnectException("Connection refused"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(new PortResult("127.0.0.1", 80, PortState.CLOSED)), results);
    }

    @Test
    void connectTimeoutIsFiltered() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new SocketTimeoutException("timed out"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(new PortResult("127.0.0.1", 80, PortState.FILTERED)), results);
    }

    @Test
    void noRouteToHostIsFiltered() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new NoRouteToHostException("no route"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(new PortResult("127.0.0.1", 80, PortState.FILTERED)), results);
    }

    @Test
    void genericIoExceptionIsFiltered() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new IOException("boom"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(new PortResult("127.0.0.1", 80, PortState.FILTERED)), results);
    }

    @Test
    void mixedPortsAreEachClassifiedIndependently() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(22, new ConnectException("Connection refused"));
        connector.failWith(443, new SocketTimeoutException("timed out"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(22, 80, 443)).withThreadCount(3);
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(
                new PortResult("127.0.0.1", 22, PortState.CLOSED),
                new PortResult("127.0.0.1", 80, PortState.OPEN),
                new PortResult("127.0.0.1", 443, PortState.FILTERED)), results);
    }

    @Test
    void resultsAreAscendingByPortRegardlessOfCompletionOrder() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        connector.failWith(80, new ConnectException("Connection refused"));
        connector.failWith(22, new SocketTimeoutException("timed out"));
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(443, 22, 80)).withThreadCount(3);
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        List<PortResult> results = scanner.scan();

        assertEquals(List.of(22, 80, 443), results.stream().map(PortResult::port).toList());
    }

    @Test
    void everyRequestedPortIsProbedExactlyOnce() throws Exception {
        FakeSocketConnector connector = new FakeSocketConnector();
        List<Integer> ports = List.of(21, 22, 23, 80, 443);
        ScanRequest request = ScanRequest.of("127.0.0.1", ports).withThreadCount(4);
        PortScanner scanner = new PortScanner(request, connector, Executors.defaultThreadFactory());

        scanner.scan();

        assertEquals(Set.copyOf(ports), connector.probedPorts());
    }
}
