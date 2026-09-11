package com.argus.core;

import java.util.List;
import java.util.TreeSet;

/** Turns user-facing port specifications into a validated, sorted, distinct port list. */
public final class PortSpec {

    private PortSpec() {}

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    /** "22,80,443,8000-8010" (whitespace tolerated) -&gt; [22, 80, 443, 8000..8010]. */
    public static List<Integer> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Port spec must not be null or blank");
        }
        TreeSet<Integer> ports = new TreeSet<>();
        for (String element : spec.split(",", -1)) {
            String trimmed = element.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Port spec has an empty element: " + spec);
            }
            if (trimmed.indexOf('-') >= 0) {
                ports.addAll(parseRangeElement(trimmed));
            } else {
                ports.add(parsePort(trimmed));
            }
        }
        return List.copyOf(ports);
    }

    /** Inclusive range. */
    public static List<Integer> range(int fromInclusive, int toInclusive) {
        validatePort(fromInclusive);
        validatePort(toInclusive);
        if (fromInclusive > toInclusive) {
            throw new IllegalArgumentException(
                    "Range is reversed: " + fromInclusive + "-" + toInclusive);
        }
        TreeSet<Integer> ports = new TreeSet<>();
        for (int port = fromInclusive; port <= toInclusive; port++) {
            ports.add(port);
        }
        return List.copyOf(ports);
    }

    private static List<Integer> parseRangeElement(String element) {
        String[] parts = element.split("-", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Malformed port range: " + element);
        }
        int from = parsePort(parts[0].trim());
        int to = parsePort(parts[1].trim());
        if (from > to) {
            throw new IllegalArgumentException("Range is reversed: " + element);
        }
        return range(from, to);
    }

    private static int parsePort(String token) {
        int value;
        try {
            value = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid port number: " + token, e);
        }
        validatePort(value);
        return value;
    }

    private static void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    "Port out of range [" + MIN_PORT + ", " + MAX_PORT + "]: " + port);
        }
    }
}
