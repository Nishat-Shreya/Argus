package com.argus.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One CVE identifier, canonical uppercase. A value type rather than a bare String because
 * KevScorer (P2-07) matches these against the CISA KEV catalog by set intersection, and
 * "cve-2021-44228" vs "CVE-2021-44228" would be a silent miss.
 */
public record CveId(String id) {

    private static final Pattern CVE_PATTERN = Pattern.compile("CVE-(\\d{4})-\\d{4,}");

    public CveId {
        Objects.requireNonNull(id, "id must not be null");
        id = id.trim().toUpperCase(Locale.ROOT);
        if (!CVE_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("not a valid CVE id: " + id);
        }
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            new CveId(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The CVE-YYYY component, for display ordering. */
    public int year() {
        Matcher matcher = CVE_PATTERN.matcher(id);
        matcher.matches();
        return Integer.parseInt(matcher.group(1));
    }
}
