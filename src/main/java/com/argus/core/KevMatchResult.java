package com.argus.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Matches for one CVE set against a {@link KevCatalog}, plus the derived score. Follows the
 * {@code ShodanHostReport} / {@code CensysAssetReport} / {@code VirusTotalReport} pattern: a
 * record holding the facts, with the score rule as a derived method — the score can never be
 * constructed inconsistently with the matches.
 *
 * NO {@code catalogVersion} COMPONENT — deliberately. A catalog version attached to a result
 * would make two otherwise-identical KEV scorings unequal across a catalog refresh, which is
 * exactly what P2-01 ruled out for {@link IntelResult}. {@code catalogVersion} stays on
 * {@link KevCatalog}, never here.
 */
public record KevMatchResult(List<KevEntry> matches) {

    /** Points for one KEV-listed CVE. */
    public static final int KEV_MATCH_POINTS = 20;
    /** Points for one KEV-listed CVE flagged knownRansomwareCampaignUse = "Known". */
    public static final int RANSOMWARE_KEV_POINTS = 50;
    public static final int MAX_SCORE = 100;

    public KevMatchResult {
        Objects.requireNonNull(matches, "matches must not be null");
        TreeSet<KevEntry> sortedDistinct =
                new TreeSet<>(Comparator.comparing(e -> e.cveId().id()));
        for (KevEntry match : matches) {
            Objects.requireNonNull(match, "matches must not contain a null element");
            if (!sortedDistinct.add(match)) {
                throw new IllegalArgumentException(
                        "duplicate cveId in matches: " + match.cveId());
            }
        }
        matches = List.copyOf(sortedDistinct);
    }

    public static KevMatchResult none() {
        return new KevMatchResult(List.of());
    }

    /** min(100, 20*plainMatches + 50*ransomwareMatches). 0 when there are no matches. */
    public int score() {
        int ransomwareCount = ransomwareCount();
        int plainCount = matches.size() - ransomwareCount;
        int raw = KEV_MATCH_POINTS * plainCount + RANSOMWARE_KEV_POINTS * ransomwareCount;
        return Math.min(MAX_SCORE, raw);
    }

    public boolean hasMatches() {
        return !matches.isEmpty();
    }

    public int matchCount() {
        return matches.size();
    }

    public int ransomwareCount() {
        int count = 0;
        for (KevEntry match : matches) {
            if (match.knownRansomwareCampaignUse()) {
                count++;
            }
        }
        return count;
    }

    public List<CveId> matchedCveIds() {
        List<CveId> ids = new ArrayList<>();
        for (KevEntry match : matches) {
            ids.add(match.cveId());
        }
        return List.copyOf(ids);
    }

    public List<KevEntry> ransomwareMatches() {
        List<KevEntry> result = new ArrayList<>();
        for (KevEntry match : matches) {
            if (match.knownRansomwareCampaignUse()) {
                result.add(match);
            }
        }
        return List.copyOf(result);
    }
}
