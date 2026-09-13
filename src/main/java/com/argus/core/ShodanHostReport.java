package com.argus.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The facts extracted from one Shodan host lookup, plus the verdict/score mapping rule (plan
 * §3.4). Pure value type: no HTTP, no vault, no JavaFX, no clock.
 */
record ShodanHostReport(
        boolean hasHostData,
        List<CveId> cveIds,
        int verifiedVulnCount,
        Map<String, String> attributes) {

    /** One distinct known CVE = 10 points; the score saturates at 100 from 10 CVEs up. */
    static final int POINTS_PER_CVE = 10;

    ShodanHostReport {
        Objects.requireNonNull(cveIds, "cveIds must not be null");
        cveIds = List.copyOf(cveIds);
        if (verifiedVulnCount < 0) {
            throw new IllegalArgumentException(
                    "verifiedVulnCount must not be negative: " + verifiedVulnCount);
        }
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(new LinkedHashMap<>(attributes));
    }

    int cveCount() {
        return cveIds.size();
    }

    /** §3.4 Decision C — driven by CVE count alone; never MALICIOUS. */
    IntelVerdict verdict() {
        if (!hasHostData) {
            return IntelVerdict.UNKNOWN;
        }
        if (cveCount() == 0) {
            return IntelVerdict.HARMLESS;
        }
        return IntelVerdict.SUSPICIOUS;
    }

    /** 0 when !hasHostData (defensive: IntelResult requires UNKNOWN to carry score 0). */
    int score() {
        if (!hasHostData) {
            return 0;
        }
        return Math.min(IntelResult.MAX_SCORE, POINTS_PER_CVE * cveCount());
    }

    IntelResult toIntelResult(IntelSubject subject) {
        return new IntelResult(ShodanSource.NAME, subject, verdict(), score(), cveIds, attributes);
    }
}
