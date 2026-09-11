package com.argus.core;

import java.util.Objects;

/**
 * The outcome of one TCP connect probe against one host/port. Structural value type — no
 * banner, no timestamp, no latency (see the plan §3.2/§7.1): {@code ScanDiffEngine} (P2-08)
 * compares scans as sets, so any per-result field that varies run-to-run would break that.
 */
public record PortResult(String host, int port, PortState state) {

    public PortResult {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (port < PortSpec.MIN_PORT || port > PortSpec.MAX_PORT) {
            throw new IllegalArgumentException(
                    "port out of range [" + PortSpec.MIN_PORT + ", " + PortSpec.MAX_PORT
                            + "]: " + port);
        }
        Objects.requireNonNull(state, "state must not be null");
    }

    public boolean isOpen() {
        return state == PortState.OPEN;
    }
}
