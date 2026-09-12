package com.argus.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A canonical operator identity. Filename-safe by construction (plan §7.3): the charset is a
 * strict subset of what every supported filesystem accepts, so mapping an id to a vault
 * filename needs no escaping and cannot traverse a directory.
 *
 * The single source of truth for "is this a well-formed operator id" — com.argus.ui calls
 * isValid(...) to satisfy invariant 8 without putting policy in the UI layer (the DomainName
 * precedent, P1-02 §3.1).
 */
public record OperatorId(String value) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;

    private static final Pattern ALLOWED_CHARS = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");

    /** Validates; {@code value} must already be canonical. */
    public OperatorId {
        if (value == null) {
            throw new IllegalArgumentException("operator id must not be null or blank");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "operator id must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                            + " characters: " + value);
        }
        if (!ALLOWED_CHARS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "operator id must start with a letter or digit and contain only "
                            + "lowercase letters, digits, '.', '_' or '-': " + value);
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException(
                    "operator id must not contain '..': " + value);
        }
        if (value.endsWith(".") || value.endsWith("-")) {
            throw new IllegalArgumentException(
                    "operator id must not end with '.' or '-': " + value);
        }
    }

    /** Trim + ASCII-lowercase, then validate. */
    public static OperatorId of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("operator id must not be null or blank");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("operator id must not be null or blank");
        }
        return new OperatorId(trimmed.toLowerCase(Locale.ROOT));
    }

    /** Non-throwing form for UI field validation (invariant 8). */
    public static boolean isValid(String raw) {
        try {
            of(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code value + ".vault.json"}. Contains no path separator, ever. */
    public String fileName() {
        return value + ".vault.json";
    }

    /** The canonical value. Never decorated — this string ends up in a filename. */
    @Override
    public String toString() {
        return value;
    }
}
