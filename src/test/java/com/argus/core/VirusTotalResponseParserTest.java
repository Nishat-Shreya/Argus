package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link VirusTotalResponseParser} — Jackson tree-model extraction from VT's domain/IP
 * reputation JSON (plan §3.5/§6.2). Fixture-driven; zero HTTP.
 */
class VirusTotalResponseParserTest {

    @Test
    void parsesDomainFixtureIntoExpectedCounts() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-malicious.json"), IntelSubjectKind.DOMAIN);

        assertEquals(60, report.harmless());
        assertEquals(2, report.malicious());
        assertEquals(1, report.suspicious());
        assertEquals(30, report.undetected());
        assertEquals(1, report.timeout());
    }

    @Test
    void parsesIpFixtureIntoExpectedCountsAndNetworkAttributes() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "ip-suspicious.json"), IntelSubjectKind.IP);

        assertEquals(50, report.harmless());
        assertEquals(1, report.malicious());
        assertEquals(0, report.suspicious());
        assertEquals(10, report.undetected());
        assertEquals(0, report.timeout());

        assertEquals("15169", report.attributes().get("asn"));
        assertEquals("GOOGLE", report.attributes().get("as_owner"));
        assertEquals("US", report.attributes().get("country"));
        assertEquals("8.8.8.0/24", report.attributes().get("network"));
    }

    @Test
    void domainFixtureCarriesRegistrarButNotIpOnlyAttributes() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-malicious.json"), IntelSubjectKind.DOMAIN);

        assertEquals("MarkMonitor Inc.", report.attributes().get("registrar"));
        assertFalse(report.attributes().containsKey("asn"));
        assertFalse(report.attributes().containsKey("as_owner"));
        assertFalse(report.attributes().containsKey("country"));
        assertFalse(report.attributes().containsKey("network"));
    }

    @Test
    void missingLastAnalysisStatsYieldsZeroCountsAndKeepsAttributes() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-no-analysis.json"), IntelSubjectKind.DOMAIN);

        assertEquals(0, report.harmless());
        assertEquals(0, report.malicious());
        assertEquals(0, report.suspicious());
        assertEquals(0, report.undetected());
        assertEquals(0, report.timeout());
        assertEquals(IntelVerdict.UNKNOWN, report.verdict());
        assertEquals("Fresh Registrar, Inc.", report.attributes().get("registrar"));
    }

    @Test
    void blankBodyThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class,
                () -> VirusTotalResponseParser.parse("", IntelSubjectKind.DOMAIN));
        assertThrows(IntelSourceException.class,
                () -> VirusTotalResponseParser.parse("   ", IntelSubjectKind.DOMAIN));
        assertThrows(IntelSourceException.class,
                () -> VirusTotalResponseParser.parse(null, IntelSubjectKind.DOMAIN));
    }

    @Test
    void nonJsonBodyThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> VirusTotalResponseParser.parse(
                "<html>503</html>", IntelSubjectKind.DOMAIN));
    }

    @Test
    void jsonArrayInsteadOfObjectThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class,
                () -> VirusTotalResponseParser.parse("[1,2,3]", IntelSubjectKind.DOMAIN));
    }

    @Test
    void missingDataAttributesThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> VirusTotalResponseParser.parse(
                "{\"data\":{\"id\":\"x\"}}", IntelSubjectKind.DOMAIN));
    }

    @Test
    void nonNumericStatValueIsTreatedAsAbsentNotAnError() throws Exception {
        String body = "{\"data\":{\"attributes\":{\"last_analysis_stats\":"
                + "{\"malicious\":\"3\",\"harmless\":10}}}}";

        VirusTotalReport report =
                assertDoesNotThrow(() -> VirusTotalResponseParser.parse(body, IntelSubjectKind.DOMAIN));

        assertEquals(0, report.malicious());
        assertEquals(10, report.harmless());
    }

    @Test
    void unknownExtraFieldsAreIgnored() throws Exception {
        String body = "{\"data\":{\"attributes\":{\"totally_new_vt_field\":{\"a\":1},"
                + "\"last_analysis_stats\":{\"harmless\":5,\"malicious\":0,\"suspicious\":0,"
                + "\"undetected\":2,\"timeout\":0}}}}";

        VirusTotalReport report =
                assertDoesNotThrow(() -> VirusTotalResponseParser.parse(body, IntelSubjectKind.DOMAIN));

        assertEquals(5, report.harmless());
    }

    @Test
    void longTagsValueIsTruncatedToTheAttributeLimit() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-overflowing-tags.json"), IntelSubjectKind.DOMAIN);

        String tags = report.attributes().get("tags");
        assertTrue(tags.length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH);
        assertDoesNotThrow(() -> report.toIntelResult(IntelSubject.domain("tag-heavy-example.com")));
    }

    @Test
    void attributeCountStaysUnderTheIntelResultCap() throws Exception {
        String[] domainFixtures = {
                "domain-malicious.json", "domain-harmless.json", "domain-no-analysis.json",
                "domain-overflowing-tags.json"
        };
        for (String fixture : domainFixtures) {
            VirusTotalReport report = VirusTotalResponseParser.parse(
                    Fixtures.read("virustotal", fixture), IntelSubjectKind.DOMAIN);
            assertTrue(report.attributes().size() <= IntelResult.MAX_ATTRIBUTES,
                    fixture + " had " + report.attributes().size() + " attributes");
        }

        VirusTotalReport ipReport = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "ip-suspicious.json"), IntelSubjectKind.IP);
        assertTrue(ipReport.attributes().size() <= IntelResult.MAX_ATTRIBUTES);
    }

    @Test
    void absentFieldsAreOmittedNotEmptyStrings() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-no-analysis.json"), IntelSubjectKind.DOMAIN);

        assertFalse(report.attributes().containsKey("detection_ratio"));
        assertFalse(report.attributes().containsKey("analysis_stats"));
        assertFalse(report.attributes().containsKey("reputation"));
        assertFalse(report.attributes().containsKey("community_votes"));
        assertFalse(report.attributes().containsKey("tags"));
        assertFalse(report.attributes().containsKey("categories"));
    }

    @Test
    void lastDnsRecordsIsNeverSurfaced() throws Exception {
        VirusTotalReport report = VirusTotalResponseParser.parse(
                Fixtures.read("virustotal", "domain-malicious.json"), IntelSubjectKind.DOMAIN);

        for (String key : report.attributes().keySet()) {
            assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("dns"));
        }
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("203.0.113.5"));
        }
    }
}
