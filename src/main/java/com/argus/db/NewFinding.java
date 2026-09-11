package com.argus.db;

import java.util.Objects;

/**
 * One discovery fact to be inserted against a scan. The db layer never sees {@code com.argus.core}
 * types (invariant 1); callers map their value objects onto this shape — the canonical mapping is
 * fixed in plan §7.1 and must not be re-invented per call site.
 *
 * <pre>
 *   PortResult(host, port, state) -&gt; NewFinding.port(host, port, state.name())
 *   Subdomain(name)               -&gt; NewFinding.subdomain(name)      // keeps any "*." prefix
 * </pre>
 *
 * Invariants: subject non-blank; PORT has a port in 1..65535 and a non-blank state; SUBDOMAIN has
 * neither.
 */
public record NewFinding(FindingType type, String subject, Integer port, String state) {

    public NewFinding {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        switch (type) {
            case PORT -> {
                if (port == null || port < 1 || port > 65535) {
                    throw new IllegalArgumentException(
                            "PORT finding requires a port in 1..65535: " + port);
                }
                if (state == null || state.isBlank()) {
                    throw new IllegalArgumentException(
                            "PORT finding requires a non-blank state");
                }
            }
            case SUBDOMAIN -> {
                if (port != null || state != null) {
                    throw new IllegalArgumentException(
                            "SUBDOMAIN finding must not carry a port or a state");
                }
            }
        }
    }

    public static NewFinding port(String host, int port, String state) {
        return new NewFinding(FindingType.PORT, host, port, state);
    }

    public static NewFinding subdomain(String name) {
        return new NewFinding(FindingType.SUBDOMAIN, name, null, null);
    }
}
