package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AbuseIpdbReport} — the verdict/score mapping rule in isolation (plan §3.4/§6.2). Pure
 * value type: no HTTP, no vault, no JSON.
 */
class AbuseIpdbReportTest {

    @Test
    void noDataYieldsUnknownVerdictAndScoreZero() {
        AbuseIpdbReport report = new AbuseIpdbReport(false, true, 0, Map.of());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void nonPublicAddressYieldsUnknownEvenWithDataAndRawScoreZero() {
        // Decision C: isPublic == false is UNKNOWN, not HARMLESS.
        AbuseIpdbReport report = new AbuseIpdbReport(true, false, 0, Map.of());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void zeroScorePublicYieldsHarmless() {
        AbuseIpdbReport report = new AbuseIpdbReport(true, true, 0, Map.of());
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void scoreOneYieldsSuspiciousWithNoNoiseFloor() {
        AbuseIpdbReport report = new AbuseIpdbReport(true, true, 1, Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        assertEquals(1, report.score());
    }

    @Test
    void boundaryTripleAgainstTheNamedThresholdConstant() {
        int threshold = AbuseIpdbReport.MALICIOUS_CONFIDENCE_THRESHOLD;

        AbuseIpdbReport belowThreshold = new AbuseIpdbReport(true, true, threshold - 1, Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, belowThreshold.verdict());

        AbuseIpdbReport atThreshold = new AbuseIpdbReport(true, true, threshold, Map.of());
        assertEquals(IntelVerdict.MALICIOUS, atThreshold.verdict());

        AbuseIpdbReport aboveThreshold = new AbuseIpdbReport(true, true, threshold + 1, Map.of());
        assertEquals(IntelVerdict.MALICIOUS, aboveThreshold.verdict());
    }

    @Test
    void scoreOneHundredYieldsMalicious() {
        AbuseIpdbReport report = new AbuseIpdbReport(true, true, 100, Map.of());
        assertEquals(IntelVerdict.MALICIOUS, report.verdict());
        assertEquals(100, report.score());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 42, 74, 75, 99, 100})
    void scoreIsTheIdentityOfAbuseConfidenceScoreForEveryValue(int raw) {
        AbuseIpdbReport report = new AbuseIpdbReport(true, true, raw, Map.of());
        assertEquals(raw, report.score());
    }

    @Test
    void toIntelResultCarriesSourceNameSubjectAndAlwaysEmptyCveIds() {
        AbuseIpdbReport report = new AbuseIpdbReport(true, true, 42, Map.of());
        IntelSubject subject = IntelSubject.ip("203.0.113.9");

        IntelResult result = report.toIntelResult(subject);

        assertEquals(AbuseIpdbSource.NAME, result.sourceName());
        assertEquals(subject, result.subject());
        assertTrue(result.cveIds().isEmpty());
    }

    @Test
    void toIntelResultOnNonPublicReportKeepsItsAttributesWhileBeingUnknownWithScoreZero() {
        Map<String, String> attrs = Map.of("is_public", "false");
        AbuseIpdbReport report = new AbuseIpdbReport(true, false, 0, attrs);
        IntelSubject subject = IntelSubject.ip("10.0.0.5");

        IntelResult result = assertDoesNotThrow(() -> report.toIntelResult(subject));

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals("false", result.attributes().get("is_public"));
    }

    @Test
    void twoReportsWithEqualComponentsProduceEqualIntelResults() {
        IntelSubject subject = IntelSubject.ip("198.51.100.7");
        AbuseIpdbReport first = new AbuseIpdbReport(true, true, 50, Map.of("country", "US"));
        AbuseIpdbReport second = new AbuseIpdbReport(true, true, 50, Map.of("country", "US"));

        assertEquals(first.toIntelResult(subject), second.toIntelResult(subject));
    }
}
