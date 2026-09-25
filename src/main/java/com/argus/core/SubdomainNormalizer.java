package com.argus.core;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Turns raw crt.name name tokens into the canonical, scoped, deduplicated result set. */
final class SubdomainNormalizer {

    private SubdomainNormalizer() {}

    /**
     * @param rawNames tokens from CrtNameResponseParser, any case, any whitespace, possibly
     *                 wildcarded, possibly out of scope, possibly not hostnames at all
     * @param domain   an already-normalized domain (see DomainName.normalize)
     * @return unmodifiable, ascending by name, duplicate-free
     */
    static List<Subdomain> normalize(Collection<String> rawNames, String domain) {
        TreeSet<String> canonicalNames = new TreeSet<>();

        for (String rawName : rawNames) {
            String trimmed = rawName.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String withoutTrailingDot =
                    lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;

            boolean wildcard = false;
            String remainder = withoutTrailingDot;
            if (withoutTrailingDot.startsWith("*.")) {
                wildcard = true;
                remainder = withoutTrailingDot.substring(2);
            } else if (withoutTrailingDot.startsWith("*")) {
                continue; // bare "*" or malformed wildcard: junk
            }
            if (remainder.indexOf('*') >= 0) {
                continue; // "*.*.example.com": more than one wildcard label is junk
            }

            if (!DomainName.isValid(remainder)) {
                continue;
            }
            if (!DomainName.isWithin(remainder, domain)) {
                continue;
            }

            canonicalNames.add(wildcard ? "*." + remainder : remainder);
        }

        return canonicalNames.stream().map(Subdomain::new).toList();
    }
}
