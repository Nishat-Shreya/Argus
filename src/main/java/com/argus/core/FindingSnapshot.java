package com.argus.core;

import java.util.Objects;

/**
 * One persisted finding, projected for callers outside {@code core}'s {@code db} reach (plan
 * §3.1). {@code id} is the {@code db} row id, preserved as a plain {@code long}. {@code type} is
 * the {@code FindingType.name()} token ({@code "PORT"}/{@code "SUBDOMAIN"}); {@code port} and
 * {@code state} are nullable (a subdomain has neither).
 */
public record FindingSnapshot(long id, String type, String subject, Integer port, String state) {

    public FindingSnapshot {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        // port/state MAY be null: a subdomain has no port and no state.
    }
}
