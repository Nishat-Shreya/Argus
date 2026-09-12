package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link VirusTotalReport} — the verdict/score mapping rule in isolation (plan §3.3/§6.1).
 * Pure value type: no HTTP, no vault.
 */
class VirusTotalReportTest {

    @Test
    void allZeroCountsAreUnknownWithScoreZero() {
        VirusTotalReport report = new VirusTotalReport(0, 0, 0, 0, 0, Map.of());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void harmlessMajorityWithNoDetectionsIsHarmlessWithScoreZero() {
        VirusTotalReport report = new VirusTotalReport(70, 0, 0, 20, 0, Map.of());
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
    }

    @Test
    void oneMaliciousEngineIsSuspiciousNotMalicious() {
        VirusTotalReport report = new VirusTotalReport(80, 1, 0, 10, 0, Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
    }

    @Test
    void twoMaliciousEnginesIsMalicious() {
        VirusTotalReport report = new VirusTotalReport(80, 2, 0, 10, 0, Map.of());
        assertEquals(IntelVerdict.MALICIOUS, report.verdict());
    }

    @Test
    void suspiciousOnlyIsSuspicious() {
        VirusTotalReport report = new VirusTotalReport(80, 0, 1, 10, 0, Map.of());
        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
    }

    @Test
    void timeoutCountIsExcludedFromTheDenominator() {
        VirusTotalReport withoutTimeouts = new VirusTotalReport(80, 1, 0, 10, 0, Map.of());
        VirusTotalReport withTimeouts = new VirusTotalReport(80, 1, 0, 10, 40, Map.of());
        assertEquals(withoutTimeouts.score(), withTimeouts.score());
    }

    @Test
    void scoreIsTheRoundedDetectionPercentage() {
        VirusTotalReport threeOfNinetyFour = new VirusTotalReport(60, 3, 0, 31, 0, Map.of());
        assertEquals(3, threeOfNinetyFour.score());

        VirusTotalReport oneOfThree = new VirusTotalReport(1, 1, 0, 1, 0, Map.of());
        assertEquals(33, oneOfThree.score());
    }

    @Test
    void scoreNeverExceedsMaxScore() {
        VirusTotalReport allMalicious = new VirusTotalReport(0, 90, 0, 0, 0, Map.of());
        assertEquals(100, allMalicious.score());
    }

    @Test
    void negativeCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirusTotalReport(-1, 0, 0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new VirusTotalReport(0, -1, 0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new VirusTotalReport(0, 0, -1, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new VirusTotalReport(0, 0, 0, -1, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new VirusTotalReport(0, 0, 0, 0, -1, Map.of()));
    }

    @Test
    void toIntelResultCarriesSourceNameSubjectAndEmptyCveIds() {
        VirusTotalReport report = new VirusTotalReport(80, 2, 0, 10, 0, Map.of());
        IntelSubject subject = IntelSubject.domain("example.com");

        IntelResult result = report.toIntelResult(subject);

        assertEquals(VirusTotalSource.NAME, result.sourceName());
        assertEquals(subject, result.subject());
        assertEquals(List.of(), result.cveIds());
        assertEquals(IntelVerdict.MALICIOUS, result.verdict());
    }

    @Test
    void unknownReportMayStillCarryAttributes() {
        VirusTotalReport report =
                new VirusTotalReport(0, 0, 0, 0, 0, Map.of("registrar", "Example Registrar"));
        IntelSubject subject = IntelSubject.domain("brand-new-domain.com");

        IntelResult result = assertDoesNotThrow(() -> report.toIntelResult(subject));

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals("Example Registrar", result.attributes().get("registrar"));
    }
}
