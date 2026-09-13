package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link AbuseIpdbResponseParser} — JSON extraction, attribute selection and exclusion pins,
 * truncation, malformed bodies (plan §3.6/§3.7/§6.3). Fixture-driven; zero HTTP, zero vault.
 */
class AbuseIpdbResponseParserTest {

    private static final List<String> ALL_FIXTURES = List.of(
            "check-clean.json", "check-suspicious.json", "check-malicious.json",
            "check-score-at-threshold.json", "check-score-below-threshold.json",
            "check-score-one.json", "check-private-ip.json",
            "check-whitelisted-with-score.json", "check-tor.json",
            "check-missing-score.json", "check-out-of-range-score.json",
            "check-unknown-category.json", "check-overflowing-fields.json",
            "check-empty-object.json");

    // ---- happy path ----

    @Test
    void suspiciousFixtureYieldsHasDataIsPublicAndTheRawScore() throws Exception {
        AbuseIpdbReport report =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-suspicious.json"));

        assertTrue(report.hasData());
        assertTrue(report.isPublic());
        assertEquals(42, report.abuseConfidenceScore());
    }

    @Test
    void cleanFixtureYieldsHarmlessShapedReportWithTotalReportsAttributeAbsent() throws Exception {
        AbuseIpdbReport report =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-clean.json"));

        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertFalse(report.attributes().containsKey("total_reports"));
    }

    @Test
    void categoriesAttributeIsDistinctAscendingByCodeAndCommaJoined() throws Exception {
        AbuseIpdbReport report =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-suspicious.json"));

        String categories = report.attributes().get("categories");
        assertNotNull(categories);
        assertTrue(categories.contains("SSH"));
        assertTrue(categories.contains("Brute-Force"));
        // code 18 (Brute-Force) is ascending-before code 22 (SSH).
        assertEquals("Brute-Force, SSH", categories);
    }

    @Test
    void unknownCategoryCodeDegradesToFallbackNameWithoutThrowing() throws Exception {
        AbuseIpdbReport report = assertDoesNotThrow(() -> AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-unknown-category.json")));

        assertTrue(report.attributes().get("categories").contains("Category 99"));
    }

    @Test
    void missingScoreFieldYieldsHasDataFalse() throws Exception {
        AbuseIpdbReport report = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-missing-score.json"));
        assertFalse(report.hasData());
    }

    @Test
    void outOfRangeScoreYieldsHasDataFalseAndIsNotClamped() throws Exception {
        AbuseIpdbReport report = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-out-of-range-score.json"));
        assertFalse(report.hasData());
    }

    @Test
    void emptyObjectYieldsHasDataFalseWithNoAttributeCrash() throws Exception {
        AbuseIpdbReport report = assertDoesNotThrow(() -> AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-empty-object.json")));
        assertFalse(report.hasData());
    }

    // ---- malformed input ----

    @Test
    void blankOrWhitespaceBodyThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> AbuseIpdbResponseParser.parse(""));
        assertThrows(IntelSourceException.class, () -> AbuseIpdbResponseParser.parse("   "));
    }

    @Test
    void nonJsonBodyThrowsIntelSourceExceptionWithCausePreserved() {
        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> AbuseIpdbResponseParser.parse("<html>503</html>"));
        assertNotNull(e.getCause());
    }

    @Test
    void jsonArrayRootThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> AbuseIpdbResponseParser.parse("[]"));
    }

    // ---- attribute selection ----

    @Test
    void overflowingFixtureTruncatesValuesAndBoundsAttributeCount() throws Exception {
        AbuseIpdbReport report = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-overflowing-fields.json"));

        for (var entry : report.attributes().entrySet()) {
            assertTrue(entry.getValue().length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH,
                    entry.getKey() + " exceeds MAX_ATTRIBUTE_VALUE_LENGTH");
        }
        assertTrue(report.attributes().get("isp").endsWith("…"));
        assertTrue(report.attributes().get("domain").endsWith("…"));
        assertTrue(report.attributes().get("hostnames").endsWith("…"));
        assertTrue(report.attributes().size() <= 13);
    }

    @Test
    void overflowingFixtureNeverLeaksAReportComment() throws Exception {
        AbuseIpdbReport report = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-overflowing-fields.json"));

        for (var entry : report.attributes().entrySet()) {
            assertFalse(entry.getKey().contains("ABUSEIPDB-FIXTURE-LONGCOMMENT-TOKEN"));
            assertFalse(entry.getValue().contains("ABUSEIPDB-FIXTURE-LONGCOMMENT-TOKEN"));
        }
    }

    @Test
    void noFixtureEverSurfacesAReporterIdOrReporterCountryOrAReportedAtValue() throws Exception {
        // "distinct_reporters" is a legitimate, intentional attribute key (plan §3.7) and
        // contains the substring "reporter" -- so this pins the specific per-report fields
        // (reporterId / reporterCountryCode / reportedAt), not the word "reporter" itself.
        for (String fixture : ALL_FIXTURES) {
            AbuseIpdbReport report =
                    AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", fixture));
            for (var entry : report.attributes().entrySet()) {
                String lowerKey = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "");
                assertFalse(lowerKey.contains("reporterid"), fixture + ": " + entry.getKey());
                assertFalse(lowerKey.contains("reportercountry"), fixture + ": " + entry.getKey());
                assertFalse(lowerKey.contains("reportedat"), fixture + ": " + entry.getKey());
            }
        }
    }

    @Test
    void lastReportedAtIsNeverSurfaced() throws Exception {
        for (String fixture : ALL_FIXTURES) {
            AbuseIpdbReport report =
                    AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", fixture));
            assertFalse(report.attributes().containsKey("last_reported_at"), fixture);
            for (String value : report.attributes().values()) {
                assertFalse(value.contains("T00:00:00+00:00") && value.length() < 30
                        && value.startsWith("2026"), fixture + ": " + value);
            }
        }
    }

    @Test
    void isPublicAttributePresentAndFalseOnlyForThePrivateIpFixture() throws Exception {
        AbuseIpdbReport privateReport = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-private-ip.json"));
        assertEquals("false", privateReport.attributes().get("is_public"));

        AbuseIpdbReport cleanReport =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-clean.json"));
        assertFalse(cleanReport.attributes().containsKey("is_public"));
    }

    @Test
    void isWhitelistedPresentOnlyWhenTrue() throws Exception {
        AbuseIpdbReport whitelisted = AbuseIpdbResponseParser.parse(
                Fixtures.read("abuseipdb", "check-whitelisted-with-score.json"));
        assertEquals("true", whitelisted.attributes().get("is_whitelisted"));

        AbuseIpdbReport clean =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-clean.json"));
        assertFalse(clean.attributes().containsKey("is_whitelisted"));
    }

    @Test
    void isTorPresentOnlyWhenTrue() throws Exception {
        AbuseIpdbReport tor =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-tor.json"));
        assertEquals("true", tor.attributes().get("is_tor"));

        AbuseIpdbReport clean =
                AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", "check-clean.json"));
        assertFalse(clean.attributes().containsKey("is_tor"));
    }

    @Test
    void reportWindowDaysEqualsTheSourceConstantOnEveryHasDataFixture() throws Exception {
        for (String fixture : ALL_FIXTURES) {
            AbuseIpdbReport report =
                    AbuseIpdbResponseParser.parse(Fixtures.read("abuseipdb", fixture));
            if (report.hasData()) {
                assertEquals(String.valueOf(AbuseIpdbSource.MAX_AGE_IN_DAYS),
                        report.attributes().get("report_window_days"), fixture);
            }
        }
    }
}
