package com.argus.core;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Test double for {@link SocketConnector}: scripts a per-port outcome (succeed, throw a given
 * exception, or block until released) with zero sockets and zero off-box network. {@code
 * startedLatch} and {@code releaseLatch} give cancellation tests a deterministic "worker is
 * definitely in-flight" signal without {@code Thread.sleep} (§4.3/§6.6 of the plan).
 */
final class FakeSocketConnector implements SocketConnector {

    final CountDownLatch startedLatch;
    final CountDownLatch releaseLatch;

    private final Map<Integer, Exception> failures = new ConcurrentHashMap<>();
    private final Set<Integer> gatedPorts = ConcurrentHashMap.newKeySet();
    private final Set<Integer> probedPorts = ConcurrentHashMap.newKeySet();

    FakeSocketConnector() {
        this(new CountDownLatch(0), new CountDownLatch(0));
    }

    FakeSocketConnector(CountDownLatch startedLatch, CountDownLatch releaseLatch) {
        this.startedLatch = startedLatch;
        this.releaseLatch = releaseLatch;
    }

    /** {@code port} throws {@code exception} when probed (IOException or RuntimeException). */
    void failWith(int port, Exception exception) {
        failures.put(port, exception);
    }

    /**
     * {@code port} counts down {@code startedLatch} then blocks on {@code releaseLatch} when
     * probed. Used by the cancellation tests to guarantee a worker is in-flight.
     */
    void blockOn(int port) {
        gatedPorts.add(port);
    }

    /** Every port probed so far. Thread-safe; a set because the request already deduplicates. */
    Set<Integer> probedPorts() {
        return probedPorts;
    }

    @Override
    public void probe(InetAddress address, int port, int timeoutMillis)
            throws IOException, InterruptedException {
        probedPorts.add(port);

        if (gatedPorts.contains(port)) {
            startedLatch.countDown();
            releaseLatch.await();
            return;
        }

        Exception failure = failures.get(port);
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException re) {
            throw re;
        }
        if (failure instanceof InterruptedException ie) {
            throw ie;
        }
        throw new AssertionError("Unsupported scripted failure type: " + failure.getClass());
    }
}
