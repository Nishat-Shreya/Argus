package com.argus.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The facts extracted from one Censys asset response, plus the verdict/score mapping rule
 * (plan §3.4). Pure value type: no HTTP, no vault, no JavaFX, NO CLOCK.
 *
 * Two channels, each citing its own precedent (plan §3.4 Decisions A-C):
 * <ul>
 *   <li><b>Exposure channel</b> ({@code exposureCount}) — distinct risk ids across
 *       {@code vulns[] ∪ exposures[] ∪ misconfigs[]}. Caps at {@code SUSPICIOUS}, never
 *       {@code MALICIOUS} (P2-03's reasoning, plus the ASM-tool argument).</li>
 *   <li><b>Compromise channel</b> ({@code compromiseCount}) — distinct risk ids in
 *       {@code compromises[]}. Reaches {@code MALICIOUS} (P2-04's reasoning).</li>
 * </ul>
 */
record CensysAssetReport(
        boolean hasResource,
        int exposureCount,
        int compromiseCount,
        List<CveId> cveIds,
        Map<String, String> attributes) {

    /** One distinct exposure/misconfig/vuln risk id = 10 points. Same value as P2-03's
     *  POINTS_PER_CVE, same reasoning. */
    static final int POINTS_PER_RISK = 10;

    /** One distinct compromise risk id = 50 points: a single compromise alone lands mid-scale
     *  and MALICIOUS. */
    static final int POINTS_PER_COMPROMISE = 50;

    /** §3.4 Decision B. Flagged for operator confirmation (plan §8 R6). */
    static final int COMPROMISE_MALICIOUS_THRESHOLD = 1;

    CensysAssetReport {
        if (exposureCount < 0) {
            throw new IllegalArgumentException(
                    "exposureCount must not be negative: " + exposureCount);
        }
        if (compromiseCount < 0) {
            throw new IllegalArgumentException(
                    "compromiseCount must not be negative: " + compromiseCount);
        }
        Objects.requireNonNull(cveIds, "cveIds must not be null");
        cveIds = List.copyOf(cveIds);
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /**
     * §3.4 Decision B:
     * <pre>
     * !hasResource                                      -&gt; UNKNOWN     (score 0)
     * compromiseCount &gt;= COMPROMISE_MALICIOUS_THRESHOLD -&gt; MALICIOUS
     * exposureCount   &gt;= 1                              -&gt; SUSPICIOUS
     * otherwise                                         -&gt; HARMLESS
     * </pre>
     */
    IntelVerdict verdict() {
        if (!hasResource) {
            return IntelVerdict.UNKNOWN;
        }
        if (compromiseCount >= COMPROMISE_MALICIOUS_THRESHOLD) {
            return IntelVerdict.MALICIOUS;
        }
        if (exposureCount >= 1) {
            return IntelVerdict.SUSPICIOUS;
        }
        return IntelVerdict.HARMLESS;
    }

    /**
     * §3.4 Decision C: {@code min(100, POINTS_PER_RISK * exposureCount +
     * POINTS_PER_COMPROMISE * compromiseCount)}. 0 when {@code !hasResource} (defensive:
     * {@link IntelResult} requires UNKNOWN to carry score 0).
     */
    int score() {
        if (!hasResource) {
            return 0;
        }
        return Math.min(IntelResult.MAX_SCORE,
                POINTS_PER_RISK * exposureCount + POINTS_PER_COMPROMISE * compromiseCount);
    }

    IntelResult toIntelResult(IntelSubject subject) {
        return new IntelResult(CensysSource.NAME, subject, verdict(), score(), cveIds, attributes);
    }
}
