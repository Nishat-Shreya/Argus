package com.argus.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** The real connector: new Socket() -&gt; connect(timeout) -&gt; close, in try-with-resources. */
final class TcpSocketConnector implements SocketConnector {

    @Override
    public void probe(InetAddress address, int port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
        }
    }
}
