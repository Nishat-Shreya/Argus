package com.argus.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and normalization of IP address literals — the IP-shaped twin of DomainName, and
 * the invariant-8 helper the UI calls before handing an IP to core.
 *
 * LITERALS ONLY. No name resolution: InetAddress.getByName would perform a DNS lookup for a
 * non-literal, which is both a network call inside mvn verify and a silent semantic change.
 * (InetAddress.ofLiteral() would be exactly right but arrived in Java 22; we target 21.)
 */
public final class IpAddress {

    private IpAddress() {}

    private static final Pattern IPV4_SHAPE =
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");

    /** Trim, ASCII-lowercase (IPv6 hex), then validate. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("ip address must not be null or blank");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("ip address must not be null or blank");
        }
        if (trimmed.indexOf('%') >= 0) {
            throw new IllegalArgumentException("zone ids are not supported: " + raw);
        }
        if (trimmed.indexOf('[') >= 0 || trimmed.indexOf(']') >= 0) {
            throw new IllegalArgumentException(
                    "surrounding brackets are not supported: " + raw);
        }
        if (trimmed.indexOf('/') >= 0) {
            throw new IllegalArgumentException("CIDR suffixes are not supported: " + raw);
        }
        boolean hasColon = trimmed.indexOf(':') >= 0;
        boolean hasDot = trimmed.indexOf('.') >= 0;
        if (hasColon && hasDot) {
            throw new IllegalArgumentException(
                    "IPv4-mapped tails and port suffixes are not supported: " + raw);
        }
        if (hasColon) {
            return normalizeIpv6(trimmed, raw);
        }
        return normalizeIpv4(trimmed, raw);
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

    /** True for a dotted-quad; false for a valid IPv6 and for anything invalid. */
    public static boolean isIpv4(String normalized) {
        return normalized != null && IPV4_SHAPE.matcher(normalized).matches();
    }

    private static String normalizeIpv4(String candidate, String raw) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not a valid IPv4 address: " + raw);
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !isAllDigits(part)) {
                throw new IllegalArgumentException("not a valid IPv4 octet: " + raw);
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                throw new IllegalArgumentException(
                        "leading zeros are not allowed in an octet: " + raw);
            }
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("octet out of range [0,255]: " + raw);
            }
        }
        return candidate;
    }

    private static String normalizeIpv6(String candidate, String raw) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        int doubleColonIndex = lower.indexOf("::");
        if (doubleColonIndex >= 0) {
            if (lower.indexOf("::", doubleColonIndex + 1) >= 0) {
                throw new IllegalArgumentException("at most one '::' is allowed: " + raw);
            }
            String left = lower.substring(0, doubleColonIndex);
            String right = lower.substring(doubleColonIndex + 2);
            String[] leftGroups = left.isEmpty() ? new String[0] : left.split(":", -1);
            String[] rightGroups = right.isEmpty() ? new String[0] : right.split(":", -1);
            validateHexGroups(leftGroups, raw);
            validateHexGroups(rightGroups, raw);
            if (leftGroups.length + rightGroups.length >= 8) {
                throw new IllegalArgumentException(
                        "'::' must represent at least one group of zeros: " + raw);
            }
            return String.join(":", leftGroups) + "::" + String.join(":", rightGroups);
        }
        String[] groups = lower.split(":", -1);
        if (groups.length != 8) {
            throw new IllegalArgumentException(
                    "IPv6 address must have exactly 8 groups: " + raw);
        }
        validateHexGroups(groups, raw);
        return String.join(":", groups);
    }

    private static void validateHexGroups(String[] groups, String raw) {
        for (String group : groups) {
            if (!HEX_GROUP.matcher(group).matches()) {
                throw new IllegalArgumentException("invalid IPv6 group: " + raw);
            }
        }
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
