package com.argus.core;

import java.util.Objects;

/**
 * One enrichment subject: a domain name or an IP literal, already validated and normalized.
 * Value type — structural equality is what lets ThreatIntelClient (P2-06) key results by
 * subject and what keeps any future intel diff set arithmetic.
 */
public record IntelSubject(IntelSubjectKind kind, String value) {

    public IntelSubject {
        Objects.requireNonNull(kind, "kind must not be null");
        switch (kind) {
            case DOMAIN -> value = DomainName.normalize(value);
            case IP -> value = IpAddress.normalize(value);
        }
    }

    /** Normalizes via DomainName.normalize; rejects IP literals (DomainName already does). */
    public static IntelSubject domain(String raw) {
        return new IntelSubject(IntelSubjectKind.DOMAIN, raw);
    }

    /** Normalizes via IpAddress.normalize; rejects hostnames. */
    public static IntelSubject ip(String raw) {
        return new IntelSubject(IntelSubjectKind.IP, raw);
    }
}
