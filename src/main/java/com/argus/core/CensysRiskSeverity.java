package com.argus.core;

import java.util.Locale;

/**
 * Censys's risk severity vocabulary, isolated because it is DATA WITH ITS OWN DECAY RISK
 * (P2-04's AbuseCategories precedent): Censys can add a level, and an unknown token must
 * degrade visibly rather than crash or vanish. Declaration order is display order.
 *
 * Used ONLY to build the {@code risk_severities} display attribute (plan §3.4 Decision C) —
 * deliberately no weight, no ordinal arithmetic and no score contribution.
 */
enum CensysRiskSeverity {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    UNRATED("");

    private final String token;

    CensysRiskSeverity(String token) {
        this.token = token;
    }

    String token() {
        return token;
    }

    /** null, blank or unrecognised -&gt; UNRATED. Never throws. */
    static CensysRiskSeverity of(String raw) {
        if (raw == null) {
            return UNRATED;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CensysRiskSeverity severity : values()) {
            if (severity != UNRATED && severity.token.equals(normalized)) {
                return severity;
            }
        }
        return UNRATED;
    }
}
