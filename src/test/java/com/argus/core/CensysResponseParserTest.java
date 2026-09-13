package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link CensysResponseParser} — envelope extraction, both asset kinds, malformed input, the
 * CVE union/dedup discipline and attribute selection/exclusion pins (plan §3.6/§3.7/§6.3).
 * Fixture-driven; zero HTTP, zero vault.
 */
class CensysResponseParserTest {

    private static final List<String> ALL_WEBPROPERTY_FIXTURES = List.of(
            "webproperty-clean.json", "webproperty-exposures.json",
            "webproperty-vulns-with-kev.json", "webproperty-compromised.json",
            "webproperty-compromised-and-exposed.json", "webproperty-free-tier-minimal.json",
            "webproperty-mixed-case-duplicate-cves.json", "webproperty-invalid-cve-tokens.json",
            "webproperty-blank-risk-ids.json", "webproperty-overflowing-fields.json",
            "webproperty-empty-resource.json", "webproperty-missing-result.json",
            "webproperty-forbidden-fields.json");

    private static final List<String> ALL_HOST_FIXTURES = List.of(
            "host-clean.json", "host-service-vulns.json", "host-service-compromised.json",
            "host-reputation-malicious.json", "host-banner-and-whois.json",
            "host-empty-resource.json");

    // ---- envelope & malformed input ----

    @Test
    void blankBodyThrows() {
        assertThrows(IntelSourceException.class,
                () -> CensysResponseParser.parse(IntelSubjectKind.DOMAIN, ""));
        assertThrows(IntelSourceException.class,
                () -> CensysResponseParser.parse(IntelSubjectKind.DOMAIN, "   "));
    }

    @Test
    void nullBodyThrows() {
        assertThrows(IntelSourceException.class,
                () -> CensysResponseParser.parse(IntelSubjectKind.DOMAIN, null));
    }

    @Test
    void notJsonThrows() {
        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> CensysResponseParser.parse(IntelSubjectKind.DOMAIN, "not json"));
        assertTrue(e.getCause() != null);
    }

    @Test
    void jsonArrayRootThrows() {
        assertThrows(IntelSourceException.class,
                () -> CensysResponseParser.parse(IntelSubjectKind.DOMAIN, "[1,2]"));
    }

    @Test
    void missingResultIsUnknownForBothKinds() throws Exception {
        CensysAssetReport domain = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-missing-result.json"));
        assertFalse(domain.hasResource());

        CensysAssetReport host = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "webproperty-missing-result.json"));
        assertFalse(host.hasResource());
    }

    @Test
    void emptyResourceIsUnknownForBothKinds() throws Exception {
        CensysAssetReport domain = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-empty-resource.json"));
        assertFalse(domain.hasResource());
        assertEquals(IntelVerdict.UNKNOWN, domain.verdict());

        CensysAssetReport host = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-empty-resource.json"));
        assertFalse(host.hasResource());
        assertEquals(IntelVerdict.UNKNOWN, host.verdict());
    }

    @Test
    void payloadIsReadFromResultDotResource() throws Exception {
        String body = "{\"resource\":{\"hostname\":\"decoy.example.com\"},"
                + "\"result\":{\"resource\":{\"hostname\":\"real.example.com\"},\"extensions\":{}}}";

        CensysAssetReport report = CensysResponseParser.parse(IntelSubjectKind.DOMAIN, body);

        assertTrue(report.hasResource());
        assertEquals("real.example.com", report.attributes().get("hostname"));
    }

    // ---- web property happy paths ----

    @Test
    void cleanWebPropertyIsHarmlessWithDescriptiveAttributes() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-clean.json"));

        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
        assertEquals("clean.example.com", report.attributes().get("hostname"));
        assertEquals("443", report.attributes().get("port"));
        assertEquals("tlsv1_3", report.attributes().get("tls_version"));
        assertTrue(report.attributes().get("cert_issuer").contains("Example Root CA"));
        assertTrue(report.attributes().containsKey("cert_sha256"));
        assertTrue(report.attributes().get("software").contains("nginx"));
        assertTrue(report.attributes().get("labels").contains("web"));
        assertEquals("1", report.attributes().get("endpoint_count"));
    }

    @Test
    void exposuresAndMisconfigsFeedTheExposureChannel() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-exposures.json"));

        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        assertEquals(3, report.exposureCount());
        assertEquals(0, report.compromiseCount());
    }

    @Test
    void vulnsFeedBothTheExposureChannelAndCveIds() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-vulns-with-kev.json"));

        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2021-44228"));
        assertTrue(ids.contains("CVE-2022-22965"));
    }

    @Test
    void compromisesFeedTheCompromiseChannelOnly() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-compromised.json"));

        assertEquals(1, report.compromiseCount());
        assertEquals(0, report.exposureCount());
        assertEquals(IntelVerdict.MALICIOUS, report.verdict());
    }

    @Test
    void kevCountCountsDistinctCvesWithNonEmptyKev() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-vulns-with-kev.json"));

        assertEquals("1", report.attributes().get("kev_count"));
    }

    @Test
    void freeTierMinimalResponseParsesToHarmlessNotUnknown() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-free-tier-minimal.json"));

        assertTrue(report.hasResource());
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
        assertEquals(0, report.score());
    }

    // ---- host happy paths ----

    @Test
    void hostRisksAreUnionedAcrossServices() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-service-vulns.json"));

        assertEquals(IntelVerdict.SUSPICIOUS, report.verdict());
        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2021-44228"));
        assertTrue(ids.contains("CVE-2022-22965"));
        assertEquals(2, report.exposureCount());
    }

    @Test
    void hostPortsAndServicesAreDistinctAndAscending() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-clean.json"));

        assertEquals("22, 443", report.attributes().get("ports"));
        assertTrue(report.attributes().get("services").contains("SSH"));
        assertTrue(report.attributes().get("services").contains("HTTPS"));
    }

    @Test
    void hostAsnCountryAndOsAreSurfaced() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-clean.json"));

        assertEquals("64500", report.attributes().get("asn"));
        assertEquals("Example Hosting LLC", report.attributes().get("as_name"));
        assertEquals("United States", report.attributes().get("country"));
        assertTrue(report.attributes().get("operating_system").contains("Ubuntu"));
    }

    @Test
    void reputationLevelIsAnAttributeAndNeverChangesTheVerdict() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-reputation-malicious.json"));

        assertEquals("malicious", report.attributes().get("reputation_level"));
        assertEquals(IntelVerdict.HARMLESS, report.verdict());
    }

    // ---- CVE discipline ----

    @Test
    void sameCveInDifferentCaseAcrossArraysYieldsOneCveId() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(IntelSubjectKind.DOMAIN,
                Fixtures.read("censys", "webproperty-mixed-case-duplicate-cves.json"));

        assertEquals(1, report.cveIds().size());

        // The two entries ("cve-2021-44228" in vulns[], "CVE-2021-44228" in exposures[]) are the
        // SAME underlying risk id in different casing, not two distinct findings. The
        // exposure-channel dedup must be case-insensitive too, exactly like the CVE dedup above
        // — otherwise exposureCount() and score() are silently inflated even though cveIds()
        // is correct.
        assertEquals(1, report.exposureCount());
        assertEquals(CensysAssetReport.POINTS_PER_RISK, report.score());

        IntelResult result = report.toIntelResult(IntelSubject.domain("dupe-cve.example.com"));
        assertEquals(1, result.cveIds().size());
    }

    @Test
    void invalidCveTokensAreDroppedNotThrown() throws Exception {
        CensysAssetReport report = assertDoesNotThrow(() -> CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-invalid-cve-tokens.json")));

        assertEquals(0, report.cveIds().size());
    }

    @Test
    void cveIdsAreHarvestedFromRiskArraysToo() throws Exception {
        String body = "{\"result\":{\"resource\":{\"hostname\":\"x.example.com\",\"port\":443,"
                + "\"exposures\":[{\"id\":\"CVE-2019-0211\",\"name\":\"Apache httpd\","
                + "\"severity\":\"high\",\"risk_source\":\"cve\"}]},\"extensions\":{}}}";

        CensysAssetReport report = CensysResponseParser.parse(IntelSubjectKind.DOMAIN, body);

        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2019-0211"));
    }

    @Test
    void blankRiskIdsAreCountedIndividuallyAndYieldNoCve() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-blank-risk-ids.json"));

        assertEquals(2, report.exposureCount());
        assertEquals(0, report.cveIds().size());
    }

    @Test
    void cveCountAndCveIdsSizeCannotDiverge() throws Exception {
        for (String fixture : ALL_WEBPROPERTY_FIXTURES) {
            CensysAssetReport report = CensysResponseParser.parse(
                    IntelSubjectKind.DOMAIN, Fixtures.read("censys", fixture));
            IntelResult result = report.toIntelResult(IntelSubject.domain("x.example.com"));
            assertEquals(report.cveIds().size(), result.cveIds().size(), fixture);
        }
    }

    // ---- attribute discipline ----

    @Test
    void attributeCountStaysWithinIntelResultMaxAttributes() throws Exception {
        for (String fixture : ALL_WEBPROPERTY_FIXTURES) {
            CensysAssetReport report = CensysResponseParser.parse(
                    IntelSubjectKind.DOMAIN, Fixtures.read("censys", fixture));
            assertTrue(report.attributes().size() <= 12, fixture);
        }
        for (String fixture : ALL_HOST_FIXTURES) {
            CensysAssetReport report = CensysResponseParser.parse(
                    IntelSubjectKind.IP, Fixtures.read("censys", fixture));
            assertTrue(report.attributes().size() <= 13, fixture);
        }
    }

    @Test
    void longValuesAreTruncatedToMaxAttributeValueLengthWithEllipsis() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-overflowing-fields.json"));

        String software = report.attributes().get("software");
        String labels = report.attributes().get("labels");
        assertTrue(software.length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH);
        assertTrue(software.endsWith("…"));
        assertTrue(labels.length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH);
        assertTrue(labels.endsWith("…"));
        assertTrue(report.attributes().size() <= 12);
    }

    @Test
    void absentFieldsAreOmittedNotBlank() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-free-tier-minimal.json"));

        for (String value : report.attributes().values()) {
            assertFalse(value.isBlank());
            assertFalse(value.equals("n/a"));
        }
        assertFalse(report.attributes().containsKey("tls_version"));
        assertFalse(report.attributes().containsKey("cert_issuer"));
        assertFalse(report.attributes().containsKey("risk_counts"));
        assertFalse(report.attributes().containsKey("risk_severities"));
        assertFalse(report.attributes().containsKey("kev_count"));
    }

    @Test
    void bannersAreNeverSurfaced() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-banner-and-whois.json"));

        for (String value : report.attributes().values()) {
            assertFalse(value.contains("CENSYS-FIXTURE-FORBIDDEN-BANNER"));
        }
    }

    @Test
    void httpBodiesHeadersTitlesAndScreenshotsAreNeverSurfaced() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.DOMAIN, Fixtures.read("censys", "webproperty-forbidden-fields.json"));

        for (String value : report.attributes().values()) {
            assertFalse(value.contains("CENSYS-FIXTURE-FORBIDDEN"));
        }
    }

    @Test
    void noTimestampAppearsInAnyAttributeValueOrKey() throws Exception {
        for (String fixture : ALL_WEBPROPERTY_FIXTURES) {
            CensysAssetReport report = CensysResponseParser.parse(
                    IntelSubjectKind.DOMAIN, Fixtures.read("censys", fixture));
            for (var entry : report.attributes().entrySet()) {
                String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
                assertFalse(lowerKey.contains("scan_time"), fixture);
                assertFalse(lowerKey.contains("added_at"), fixture);
                assertFalse(lowerKey.contains("not_after"), fixture);
                assertFalse(entry.getValue().contains("2026-09-01T00:00:00Z"), fixture);
                assertFalse(entry.getValue().contains("2026-12-31T00:00:00Z"), fixture);
            }
        }
    }

    @Test
    void dnsNamesAndWhoisAreNeverSurfaced() throws Exception {
        CensysAssetReport report = CensysResponseParser.parse(
                IntelSubjectKind.IP, Fixtures.read("censys", "host-banner-and-whois.json"));

        assertFalse(report.attributes().containsKey("dns_names"));
        assertFalse(report.attributes().containsKey("whois"));
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("CENSYS-FIXTURE-FORBIDDEN-DNS-NAME"));
            assertFalse(value.contains("CENSYS-FIXTURE-FORBIDDEN-REVERSE-DNS"));
            assertFalse(value.contains("CENSYS-FIXTURE-FORBIDDEN-WHOIS-RECORD"));
        }
    }
}
