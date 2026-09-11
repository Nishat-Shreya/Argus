package com.argus.core;

import java.util.Locale;
import java.util.Objects;

/**
 * One DNS name observed in a Certificate Transparency log entry. Value type: structural
 * equality is what makes dedup and ScanDiffEngine (P2-08) set arithmetic work.
 */
public record Subdomain(String name) {

    public Subdomain {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("name must already be lowercase: " + name);
        }
        String remainder;
        if (name.startsWith("*.")) {
            remainder = name.substring(2);
        } else if (name.startsWith("*")) {
            throw new IllegalArgumentException("invalid wildcard form: " + name);
        } else {
            remainder = name;
        }
        if (remainder.indexOf('*') >= 0) {
            throw new IllegalArgumentException("invalid wildcard form: " + name);
        }
        DomainName.normalize(remainder);
    }

    /** True for a wildcard entry such as {@code *.example.com}. */
    public boolean isWildcard() {
        return name.startsWith("*.");
    }

    /** The name with any {@code "*."} prefix removed — the zone the wildcard covers. */
    public String baseName() {
        return isWildcard() ? name.substring(2) : name;
    }
}
