package com.argus.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The counts extracted from a VirusTotal {@code last_analysis_stats} object, plus the
 * verdict/score mapping rule (plan §3.2/§3.3). Pure value type: no HTTP, no vault, no
 * JavaFX.
 */
record VirusTotalReport(
        int harmless, int malicious, int suspicious, int undetected, int timeout,
        Map<String, String> attributes) {

    /**
     * A single-engine detection is the classic VirusTotal false positive (plan §3.3); two or
     * more malicious engines is required before the coarse verdict escalates to MALICIOUS.
     */
    static final int MALICIOUS_ENGINE_THRESHOLD = 2;

    VirusTotalReport {
        requireNonNegative(harmless, "harmless");
        requireNonNegative(malicious, "malicious");
        requireNonNegative(suspicious, "suspicious");
        requireNonNegative(undetected, "undetected");
        requireNonNegative(timeout, "timeout");
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    /** Vendors that actually rendered an opinion. 'timeout' is deliberately excluded. */
    int analyzedEngineCount() {
        return harmless + malicious + suspicious + undetected;
    }

    /** malicious + suspicious. */
    int flaggedEngineCount() {
        return malicious + suspicious;
    }

    /** 0..100. round(100 * flagged / analyzed); 0 when analyzed == 0. */
    int score() {
        int analyzed = analyzedEngineCount();
        if (analyzed == 0) {
            return 0;
        }
        long rounded = Math.round(100.0 * flaggedEngineCount() / analyzed);
        return (int) Math.max(0, Math.min(IntelResult.MAX_SCORE, rounded));
    }

    /** Bucketed from the raw vendor counts, not the derived percentage (plan §3.3). */
    IntelVerdict verdict() {
        if (analyzedEngineCount() == 0) {
            return IntelVerdict.UNKNOWN;
        }
        if (malicious >= MALICIOUS_ENGINE_THRESHOLD) {
            return IntelVerdict.MALICIOUS;
        }
        if (malicious >= 1 || suspicious >= 1) {
            return IntelVerdict.SUSPICIOUS;
        }
        return IntelVerdict.HARMLESS;
    }

    /** Assembles the IntelResult. cveIds is always List.of() — VT reputation carries no CVEs. */
    IntelResult toIntelResult(IntelSubject subject) {
        return new IntelResult(
                VirusTotalSource.NAME, subject, verdict(), score(), List.of(), attributes);
    }
}
