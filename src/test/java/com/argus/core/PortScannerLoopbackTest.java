package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end: the real {@link TcpSocketConnector} through {@link PortScanner} against real
 * loopback sockets. Ephemeral ports only, literal {@code "127.0.0.1"} (R5 of the plan). No
 * {@code FILTERED} case here — see §7.2 for why that is injected, not simulated.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PortScannerLoopbackTest {

    private ServerSocket openServerSocket;
    private ServerSocket closedServerSocket;

    @AfterEach
    void closeSockets() throws Exception {
        if (openServerSocket != null && !openServerSocket.isClosed()) {
            openServerSocket.close();
        }
    }

    @Test
    void scansOpenAndClosedLoopbackPortsInOneRun() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        openServerSocket = new ServerSocket(0, 50, loopback);
        closedServerSocket = new ServerSocket(0, 50, loopback);
        int openPort = openServerSocket.getLocalPort();
        int closedPort = closedServerSocket.getLocalPort();
        closedServerSocket.close();

        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(openPort, closedPort))
                .withThreadCount(2)
                .withConnectTimeout(Duration.ofMillis(2000));
        PortScanner scanner = new PortScanner(request);

        List<PortResult> results = scanner.scan();

        List<Integer> sortedPorts = List.of(openPort, closedPort).stream().sorted().toList();
        assertEquals(sortedPorts, results.stream().map(PortResult::port).toList());
        for (PortResult result : results) {
            if (result.port() == openPort) {
                assertEquals(PortState.OPEN, result.state());
            } else {
                assertEquals(PortState.CLOSED, result.state());
            }
        }
    }

    @Test
    void poolIsTerminatedAfterRealScan() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        openServerSocket = new ServerSocket(0, 50, loopback);
        int openPort = openServerSocket.getLocalPort();

        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(openPort));
        PortScanner scanner = new PortScanner(request, new TcpSocketConnector(),
                Executors.defaultThreadFactory());

        scanner.scan();

        assertTrue(scanner.isPoolTerminated());
    }
}
