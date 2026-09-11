package com.argus.db;

import java.util.List;
import java.util.Objects;

/**
 * A scan and all of its findings, eagerly loaded (plan §7.8). {@code findings} is defensively
 * copied and unmodifiable.
 */
public record ScanSession(ScanRecord scan, List<FindingRecord> findings) {

    public ScanSession {
        Objects.requireNonNull(scan, "scan must not be null");
        Objects.requireNonNull(findings, "findings must not be null");
        findings = List.copyOf(findings);
    }
}
