package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CensysAssetReport} — the two-channel verdict/score mapping rule in isolation (plan
 * §3.4 Decision B/C, §6.2). Pure value type: no HTTP, no vault, no JSON.
 */
class CensysAssetReportTest {

    @Test
    void noResourceIsUnknownWithScoreZero() {
        CensysAssetReport report = new CensysAssetReport(false, 0, 0, List.of(), Map.of());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void noRisksIsHarmlessWithScoreZero() {
        CensysAssetReport report = new CensysAssetReport(true, 0, 0, List.of(), Map.of());
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void oneExposureIsSuspiciousAtPointsPerRisk() {
        CensysAssetReport report = new CensysAssetReport(true, 1, 0, List.of(), Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        assertEquals(CensysAssetReport.POINTS_PER_RISK, report.score());
    }

    @Test
    void exposuresNeverReachMalicious() {
        CensysAssetReport report = new CensysAssetReport(true, 20, 0, List.of(), Map.of());
        assertEquals(100, report.score());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
    }

    @Test
    void oneCompromiseIsMaliciousAtPointsPerCompromise() {
        CensysAssetReport report = new CensysAssetReport(true, 0, 1, List.of(), Map.of());
        assertEquals(IntelVerdict.MALICIOUS, report.verdict());
        assertEquals(CensysAssetReport.POINTS_PER_COMPROMISE, report.score());
    }

    @Test
    void compromiseWinsOverExposures() {
        CensysAssetReport report = new CensysAssetReport(true, 2, 1, List.of(), Map.of());
        assertEquals(IntelVerdict.MALICIOUS, report.verdict());
        assertEquals(CensysAssetReport.POINTS_PER_RISK * 2 + CensysAssetReport.POINTS_PER_COMPROMISE,
                report.score());
    }

    @Test
    void scoreIsCappedAtOneHundred() {
        CensysAssetReport report = new CensysAssetReport(true, 50, 5, List.of(), Map.of());
        assertEquals(100, report.score());
    }

    @Test
    void unknownAlwaysHasScoreZero() {
        CensysAssetReport report = new CensysAssetReport(false, 5, 5, List.of(), Map.of());
        assertEquals(0, report.score());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());

        IntelResult result = assertDoesNotThrow(
                () -> report.toIntelResult(IntelSubject.domain("example.com")));
        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void toIntelResultCarriesNameSubjectVerdictScoreCvesAndAttributes() {
        List<CveId> ids = List.of(new CveId("CVE-2021-44228"));
        Map<String, String> attrs = Map.of("hostname", "example.com");
        CensysAssetReport report = new CensysAssetReport(true, 1, 0, ids, attrs);
        IntelSubject subject = IntelSubject.domain("example.com");

        IntelResult result = report.toIntelResult(subject);

        assertEquals(CensysSource.NAME, result.sourceName());
        assertEquals(subject, result.subject());
        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        assertEquals(CensysAssetReport.POINTS_PER_RISK, result.score());
        assertEquals(1, result.cveIds().size());
        assertEquals("example.com", result.attributes().get("hostname"));
    }

    @Test
    void constructorRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CensysAssetReport(true, -1, 0, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CensysAssetReport(true, 0, -1, List.of(), Map.of()));
    }

    @Test
    void attributesAndCveIdsAreDefensivelyCopied() {
        List<CveId> mutableIds = new java.util.ArrayList<>(List.of(new CveId("CVE-2021-44228")));
        Map<String, String> mutableAttrs = new LinkedHashMap<>();
        mutableAttrs.put("hostname", "example.com");

        CensysAssetReport report = new CensysAssetReport(true, 1, 0, mutableIds, mutableAttrs);

        mutableIds.add(new CveId("CVE-2021-19999"));
        mutableAttrs.put("port", "443");

        assertEquals(1, report.cveIds().size());
        assertEquals(1, report.attributes().size());
    }

    @Test
    void namedConstantsHaveThePlannedValues() {
        assertEquals(10, CensysAssetReport.POINTS_PER_RISK);
        assertEquals(50, CensysAssetReport.POINTS_PER_COMPROMISE);
        assertEquals(1, CensysAssetReport.COMPROMISE_MALICIOUS_THRESHOLD);
        assertTrue(CensysAssetReport.COMPROMISE_MALICIOUS_THRESHOLD >= 1);
    }
}
