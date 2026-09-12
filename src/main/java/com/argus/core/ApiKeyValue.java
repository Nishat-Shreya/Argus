package com.argus.core;

/**
 * Validation and normalization of an API key VALUE. The single source of truth for "is this
 * storable" — com.argus.ui calls isValid(...) to satisfy invariant 8 (the DomainName precedent).
 *
 * This is a TRANSPORT rule, not a per-provider format rule (plan §7.4): a key must survive being
 * put in an HTTP header value (VirusTotal, AbuseIPDB, Censys) or a URL query parameter (Shodan)
 * without injection or encoding surprises. It does NOT know that a VirusTotal key is 64 hex
 * characters, and must not learn.
 *
 * INVARIANT 7, and the one place this class diverges from DomainName/OperatorId: NO exception
 * message, and no other output of this class, may contain any part of the candidate value.
 * This class never logs.
 */
public final class ApiKeyValue {

    /** Upper bound on a stored key. Bounded like Vault.MAX_NAME_LENGTH, not provider-derived. */
    public static final int MAX_LENGTH = 512;

    private ApiKeyValue() {
    }

    /** Unicode-aware strip() of surrounding whitespace. Does not validate. Null-safe -&gt; null. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip();
    }

    /**
     * True if normalize(raw) is non-empty, at most MAX_LENGTH characters, and consists only of
     * printable ASCII excluding space (U+0021..U+007E).
     */
    public static boolean isValid(String raw) {
        String normalized = normalize(raw);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (normalized.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * normalize + validate.
     *
     * @throws IllegalArgumentException with a message that NEVER quotes the value
     */
    public static String require(String raw) {
        String normalized = normalize(raw);
        if (!isValid(raw)) {
            if (normalized == null || normalized.isEmpty()) {
                throw new IllegalArgumentException("a key is required");
            }
            if (normalized.length() > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "key exceeds " + MAX_LENGTH + " characters");
            }
            throw new IllegalArgumentException(
                    "key must be printable ASCII with no spaces or control characters");
        }
        return normalized;
    }
}
