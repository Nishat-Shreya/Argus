package com.argus.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Builds {@link IntelSubject}s from discovery output. Discharges P2-05's carry-forward: a
 * wildcard {@link Subdomain} (e.g. {@code *.example.com}) is a DNS <em>policy</em> statement,
 * not an observed host, so it is skipped rather than substituted with its base zone — the same
 * class of error as synthesizing a finding crt.sh never asserted.
 */
public final class IntelSubjects {

    private IntelSubjects() {}

    /** Empty for a wildcard entry (never the base zone — see plan §3.7). */
    public static Optional<IntelSubject> forSubdomain(Subdomain subdomain) {
        Objects.requireNonNull(subdomain, "subdomain must not be null");
        if (subdomain.isWildcard()) {
            return Optional.empty();
        }
        return Optional.of(IntelSubject.domain(subdomain.name()));
    }
}
