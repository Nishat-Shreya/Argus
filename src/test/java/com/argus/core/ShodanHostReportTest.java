package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ShodanHostReport} — the verdict/score mapping rule in isolation (plan §3.4/§6.1).
 * Pure value type: no HTTP, no vault, no JSON.
 */
class ShodanHostReportTest {

    private static List<CveId> cveIds(int n) {
        List<CveId> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(new CveId(String.format("CVE-2021-%05d", 10000 + i)));
        }
        return ids;
    }

    @Test
    void noHostDataYieldsUnknownVerdictAndScoreZero() {
        ShodanHostReport report = new ShodanHostReport(false, List.of(), 0, Map.of());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void hostDataWithNoCvesYieldsHarmlessAndScoreZero() {
        ShodanHostReport report = new ShodanHostReport(true, List.of(), 0, Map.of());
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void oneCveYieldsSuspiciousAndScoreTen() {
        ShodanHostReport report = new ShodanHostReport(true, cveIds(1), 0, Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        assertEquals(10, report.score());
    }

    @Test
    void fiveCvesYieldScoreFifty() {
        ShodanHostReport report = new ShodanHostReport(true, cveIds(5), 0, Map.of());
        assertEquals(50, report.score());
    }

    @Test
    void tenCvesSaturateScoreAtOneHundred() {
        ShodanHostReport report = new ShodanHostReport(true, cveIds(10), 0, Map.of());
        assertEquals(100, report.score());
    }

    @Test
    void twentyCvesStillScoreOneHundredAndNeverExceedMaxScore() {
        ShodanHostReport report = new ShodanHostReport(true, cveIds(20), 0, Map.of());
        assertEquals(100, report.score());
        assertTrue(report.score() <= IntelResult.MAX_SCORE);
    }

    @Test
    void verdictIsNeverMaliciousForAnyCveCount() {
        for (int n = 0; n <= 50; n++) {
            ShodanHostReport report = new ShodanHostReport(true, cveIds(n), 0, Map.of());
            assertNotEquals(IntelVerdict.MALICIOUS, report.verdict(),
                    "verdict went MALICIOUS at n=" + n);
        }
    }

    @Test
    void scoreIsZeroWhenNoHostDataEvenIfCveIdsPresent() {
        ShodanHostReport report = new ShodanHostReport(false, cveIds(5), 0, Map.of());
        assertEquals(0, report.score());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());

        IntelResult result = assertDoesNotThrow(
                () -> report.toIntelResult(IntelSubject.ip("203.0.113.9")));
        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void toIntelResultCarriesSourceNameSubjectVerdictScoreCvesAndAttributes() {
        List<CveId> ids = cveIds(3);
        Map<String, String> attrs = Map.of("org", "Example Hosting LLC");
        ShodanHostReport report = new ShodanHostReport(true, ids, 0, attrs);
        IntelSubject subject = IntelSubject.ip("203.0.113.9");

        IntelResult result = report.toIntelResult(subject);

        assertEquals(ShodanSource.NAME, result.sourceName());
        assertEquals(subject, result.subject());
        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        assertEquals(30, result.score());
        assertEquals(3, result.cveIds().size());
        assertEquals("Example Hosting LLC", result.attributes().get("org"));
    }

    @Test
    void cveIdsAndAttributesAreDefensivelyCopied() {
        List<CveId> mutableIds = new ArrayList<>(cveIds(2));
        Map<String, String> mutableAttrs = new LinkedHashMap<>();
        mutableAttrs.put("org", "Example Hosting LLC");

        ShodanHostReport report = new ShodanHostReport(true, mutableIds, 0, mutableAttrs);

        mutableIds.add(new CveId("CVE-2021-19999"));
        mutableAttrs.put("isp", "Another ISP");

        assertEquals(2, report.cveIds().size());
        assertEquals(1, report.attributes().size());
    }

    @Test
    void negativeVerifiedVulnCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShodanHostReport(true, List.of(), -1, Map.of()));
    }

    @Test
    void pointsPerCveIsANamedConstantNotTen() {
        int points = ShodanHostReport.POINTS_PER_CVE;
        assertEquals(10, points);

        ShodanHostReport oneCve = new ShodanHostReport(true, cveIds(1), 0, Map.of());
        assertEquals(Math.min(IntelResult.MAX_SCORE, points * 1), oneCve.score());

        ShodanHostReport fourCves = new ShodanHostReport(true, cveIds(4), 0, Map.of());
        assertEquals(Math.min(IntelResult.MAX_SCORE, points * 4), fourCves.score());
    }
}
