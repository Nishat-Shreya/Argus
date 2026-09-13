package com.argus.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The facts extracted from one AbuseIPDB {@code /check} response, plus the verdict/score
 * mapping rule (plan §3.4). Pure value type: no HTTP, no vault, no JavaFX, no clock.
 */
record AbuseIpdbReport(
        boolean hasData,
        boolean isPublic,
        int abuseConfidenceScore,
        Map<String, String> attributes) {

    /** §3.4 Decision B. The one tunable number in this item. */
    static final int MALICIOUS_CONFIDENCE_THRESHOLD = 75;

    AbuseIpdbReport {
        if (abuseConfidenceScore < 0 || abuseConfidenceScore > IntelResult.MAX_SCORE) {
            throw new IllegalArgumentException(
                    "abuseConfidenceScore out of range [0," + IntelResult.MAX_SCORE + "]: "
                            + abuseConfidenceScore);
        }
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /**
     * §3.4 Decision B/C. UNKNOWN when there is no usable data, or the address is not public
     * (Decision C: a private/reserved address is "cannot know", not "clean"). Otherwise
     * HARMLESS at 0, SUSPICIOUS below the threshold with no noise floor, MALICIOUS at or above
     * it — MALICIOUS is reachable here, unlike ShodanHostReport.
     */
    IntelVerdict verdict() {
        if (!hasData || !isPublic) {
            return IntelVerdict.UNKNOWN;
        }
        if (abuseConfidenceScore == 0) {
            return IntelVerdict.HARMLESS;
        }
        if (abuseConfidenceScore >= MALICIOUS_CONFIDENCE_THRESHOLD) {
            return IntelVerdict.MALICIOUS;
        }
        return IntelVerdict.SUSPICIOUS;
    }

    /** Decision A: identity mapping. 0 when UNKNOWN (IntelResult requires UNKNOWN -&gt; 0). */
    int score() {
        if (!hasData || !isPublic) {
            return 0;
        }
        return abuseConfidenceScore;
    }

    /** Assembles the IntelResult. cveIds is ALWAYS List.of() (plan §3.8): AbuseIPDB reports
     *  abuse categories, not CVEs. */
    IntelResult toIntelResult(IntelSubject subject) {
        return new IntelResult(
                AbuseIpdbSource.NAME, subject, verdict(), score(), List.of(), attributes);
    }
}
