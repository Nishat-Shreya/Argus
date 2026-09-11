package com.argus.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and normalization of DNS names. The single source of truth for "is this a
 * well-formed domain" — com.argus.ui calls isValid(...) to satisfy invariant 8 without
 * putting business logic in the UI layer. Basic LDH validation, not RFC 1035-complete
 * (plan §3.1/§7.5): no IDN conversion, no public-suffix list.
 */
public final class DomainName {

    private DomainName() {}

    public static final int MAX_LENGTH = 253;
    public static final int MAX_LABEL_LENGTH = 63;

    private static final Pattern LDH_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    /** Trim, ASCII-lowercase, drop one trailing root dot, then validate. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("domain must not be null or blank");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("domain must not be null or blank");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String withoutTrailingDot =
                lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;

        if (withoutTrailingDot.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "domain exceeds " + MAX_LENGTH + " characters: " + raw);
        }

        String[] labels = withoutTrailingDot.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException(
                    "domain must have at least two labels: " + raw);
        }
        for (String label : labels) {
            validateLabel(label);
        }
        if (!containsLetter(labels[labels.length - 1])) {
            throw new IllegalArgumentException(
                    "last label must contain a letter (rejects IP literals): " + raw);
        }
        return withoutTrailingDot;
    }

    /** Non-throwing form for UI field validation. */
    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True if {@code candidate} equals {@code domain} or is a strict subdomain of it. */
    public static boolean isWithin(String candidate, String domain) {
        if (candidate == null || domain == null) {
            return false;
        }
        return candidate.equals(domain) || candidate.endsWith("." + domain);
    }

    private static void validateLabel(String label) {
        if (label.isEmpty()) {
            throw new IllegalArgumentException("domain has an empty label");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(
                    "label exceeds " + MAX_LABEL_LENGTH + " characters: " + label);
        }
        if (!isAscii(label)) {
            throw new IllegalArgumentException(
                    "non-ASCII characters are not supported; convert to punycode first: "
                            + label);
        }
        if (!LDH_LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException(
                    "label has invalid characters or leading/trailing hyphen: " + label);
        }
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsLetter(String label) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }
}
