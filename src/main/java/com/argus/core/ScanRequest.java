package com.argus.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Immutable configuration for one {@link PortScanner} run: target host, port list, worker
 * thread count and connect timeout. {@code of(...)} applies the defaults; {@code withX}
 * methods produce a modified copy (§3.4 of the plan — withers, not a builder).
 */
public record ScanRequest(String host, List<Integer> ports, int threadCount,
        Duration connectTimeout) {

    public static final int DEFAULT_THREAD_COUNT = 16;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(1000);
    public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(60);

    public ScanRequest {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        host = host.trim();

        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("ports must not be null or empty");
        }
        TreeSet<Integer> sortedDistinct = new TreeSet<>();
        for (Integer port : ports) {
            if (port == null) {
                throw new IllegalArgumentException("ports must not contain null values");
            }
            if (port < PortSpec.MIN_PORT || port > PortSpec.MAX_PORT) {
                throw new IllegalArgumentException(
                        "port out of range [" + PortSpec.MIN_PORT + ", " + PortSpec.MAX_PORT
                                + "]: " + port);
            }
            sortedDistinct.add(port);
        }
        ports = List.copyOf(sortedDistinct);

        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1: " + threadCount);
        }

        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "connectTimeout must be strictly positive: " + connectTimeout);
        }
        if (connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "connectTimeout must be <= " + MAX_CONNECT_TIMEOUT + ": " + connectTimeout);
        }
    }

    public static ScanRequest of(String host, List<Integer> ports) {
        return new ScanRequest(host, ports, DEFAULT_THREAD_COUNT, DEFAULT_CONNECT_TIMEOUT);
    }

    public ScanRequest withThreadCount(int threadCount) {
        return new ScanRequest(host, ports, threadCount, connectTimeout);
    }

    public ScanRequest withConnectTimeout(Duration connectTimeout) {
        return new ScanRequest(host, ports, threadCount, connectTimeout);
    }

    /** Connect timeout as a strictly positive int, safe to hand to Socket.connect. */
    public int connectTimeoutMillis() {
        return Math.toIntExact(connectTimeout.toMillis());
    }
}
