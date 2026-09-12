package com.argus.ui;

import com.argus.core.DomainName;
import com.argus.core.PortSpec;
import java.util.List;

/** An already-validated scan request the coordinator executes. Immutable. */
record ScanPlan(String target, List<Integer> ports) {

    /**
     * The fixed port set P1-06 scans. A UI port field is out of scope (§9); this constant is
     * the single place the choice is written down. Built through {@link PortSpec} so the
     * parsing rule stays in {@code core} (R10).
     */
    static final List<Integer> DEFAULT_PORTS = PortSpec.parse(
            "21,22,23,25,53,80,110,143,443,445,993,995,1433,3306,3389,5432,5900,8000,8080,8443");

    ScanPlan {
        if (target == null || target.isBlank() || !DomainName.isValid(target)) {
            throw new IllegalArgumentException("target must be a valid domain: " + target);
        }
        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("ports must not be null or empty");
        }
        ports = List.copyOf(ports);
    }

    static ScanPlan of(String normalizedTarget) {
        return new ScanPlan(normalizedTarget, DEFAULT_PORTS);
    }
}
