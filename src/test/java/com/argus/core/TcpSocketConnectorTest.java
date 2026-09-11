package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link TcpSocketConnector} against real loopback sockets only — no off-box network, no
 * fixed port numbers (ephemeral ports via {@code new ServerSocket(0)}), literal
 * {@code "127.0.0.1"} never {@code "localhost"} (R5 of the plan).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TcpSocketConnectorTest {

    private ServerSocket serverSocket;

    @AfterEach
    void closeServerSocket() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    void probeReturnsNormallyForListeningPort() throws Exception {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        TcpSocketConnector connector = new TcpSocketConnector();

        assertDoesNotThrow(() -> connector.probe(
                InetAddress.getByName("127.0.0.1"), serverSocket.getLocalPort(), 2000));
    }

    @Test
    void probeThrowsConnectExceptionForRefusedPort() throws Exception {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int refusedPort = serverSocket.getLocalPort();
        serverSocket.close();

        TcpSocketConnector connector = new TcpSocketConnector();

        assertThrows(ConnectException.class, () -> connector.probe(
                InetAddress.getByName("127.0.0.1"), refusedPort, 2000));
    }

    @Test
    void probeClosesItsSocket() throws Exception {
        // Asserts client-side closure directly, not via backlog-slot draining: accept the
        // connection the probe makes, then read from the server side of it. If probe() closed
        // its socket (the try-with-resources contract), the server sees EOF (read() == -1).
        // If it leaked the socket instead, the read blocks until the bounded SoTimeout below
        // fires -- a failure bound, not a timing-as-correctness-proxy (same pattern as the
        // plan's @Timeout / latch.await usage elsewhere).
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int port = serverSocket.getLocalPort();

        ExecutorService acceptorPool = Executors.newSingleThreadExecutor();
        try {
            Future<Socket> acceptedFuture = acceptorPool.submit(serverSocket::accept);

            TcpSocketConnector connector = new TcpSocketConnector();
            connector.probe(loopback, port, 2000);

            try (Socket accepted = acceptedFuture.get(5, TimeUnit.SECONDS)) {
                accepted.setSoTimeout(5000);
                int eof = accepted.getInputStream().read();
                assertEquals(-1, eof,
                        "server-side read should observe EOF once the client (probe) closes its socket");
            }
        } finally {
            acceptorPool.shutdownNow();
        }
    }
}
