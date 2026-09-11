package com.argus.core;

import java.io.IOException;
import java.net.InetAddress;

/**
 * The connect-probe seam: package-private so {@link PortScanner} can be tested without a
 * network by swapping in a fake, without enlarging the public API.
 */
@FunctionalInterface
interface SocketConnector {
    /**
     * Attempt one TCP connect and close it immediately.
     * Returns normally iff the port accepted the connection.
     */
    void probe(InetAddress address, int port, int timeoutMillis)
            throws IOException, InterruptedException;
}
