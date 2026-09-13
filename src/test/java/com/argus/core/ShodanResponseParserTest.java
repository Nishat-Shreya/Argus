package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ShodanResponseParser} — the two-shape {@code vulns} union walk, invalid-token
 * dropping, attribute selection and exclusion pins, truncation, malformed bodies (plan
 * §3.6/§3.7/§6.2). Fixture-driven; zero HTTP, zero vault.
 */
class ShodanResponseParserTest {

    private static final Set<String> ATTRIBUTE_VOCABULARY = Set.of(
            "ports", "port_count", "services", "hostnames", "org", "isp", "asn", "os",
            "country", "tags", "vuln_count", "verified_vulns");

    // ---- happy / CVE union ----

    @Test
    void hostLevelVulnsArrayOfStringsIsParsedIntoCveIds() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-overlapping-vulns.json"));

        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2021-44228"));
        assertTrue(ids.contains("CVE-2022-22965"));
    }

    @Test
    void bannerLevelVulnsObjectKeysAreParsedIntoCveIds() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-banner-vulns-only.json"));

        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2014-0226"));
        assertTrue(ids.contains("CVE-2017-3167"));
        assertEquals(2, report.cveCount());
    }

    @Test
    void hostAndBannerVulnsAreUnionedAndDeduplicated() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-overlapping-vulns.json"));

        // Host-level array has CVE-2021-44228 and CVE-2022-22965; banner repeats
        // CVE-2021-44228. Union must dedupe to 2 distinct CVEs.
        assertEquals(2, report.cveCount());
    }

    @Test
    void crossShapeCveDuplicateInDifferentCasingDedupesToOneDistinctCve() throws Exception {
        // The host-level `vulns` array carries "cve-2021-44228" (lowercase); a banner's
        // `vulns` object carries the key "CVE-2021-44228" (uppercase). Same CVE, different
        // casing, across the two shapes -- must collapse to exactly one distinct CveId, not
        // two, so that ShodanHostReport.cveCount()/score() can never diverge from the
        // eventual IntelResult.cveIds().size() (reviewer-found correctness bug).
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-overlapping-vulns-mixed-case.json"));

        assertEquals(1, report.cveCount());
        assertEquals(ShodanHostReport.POINTS_PER_CVE, report.score());

        IntelResult result = report.toIntelResult(IntelSubject.ip("198.51.100.40"));
        assertEquals(1, result.cveIds().size());
        assertEquals(report.cveCount(), result.cveIds().size());
        assertEquals(report.score(), result.score());
    }

    @Test
    void lowercaseCveTokenIsCanonicalizedToUppercase() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        List<String> ids = report.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2020-1938"));
        for (String id : ids) {
            assertEquals(id.toUpperCase(Locale.ROOT), id);
        }
    }

    @Test
    void verifiedVulnCountCountsOnlyBannerVulnsWithVerifiedTrue() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-banner-vulns-only.json"));

        assertEquals(1, report.verifiedVulnCount());
    }

    // ---- malformed input ----

    @Test
    void nonCveVulnTokensAreDroppedNotThrown() throws Exception {
        String body = "{\"ip_str\":\"198.51.100.30\",\"ports\":[80],"
                + "\"vulns\":[\"MSF:EXPLOIT/X\",\"not-a-cve\",\"\",123],"
                + "\"data\":[{\"port\":80,\"vulns\":{\"alsoNotACve\":{}}}]}";

        ShodanHostReport report = assertDoesNotThrow(() -> ShodanResponseParser.parse(body));
        assertEquals(0, report.cveCount());
    }

    @Test
    void vulnsOfWrongJsonTypeDegradesToNoCves() throws Exception {
        ShodanHostReport nope = assertDoesNotThrow(
                () -> ShodanResponseParser.parse("{\"vulns\":\"nope\"}"));
        assertEquals(0, nope.cveCount());

        ShodanHostReport seven = assertDoesNotThrow(
                () -> ShodanResponseParser.parse("{\"vulns\":7}"));
        assertEquals(0, seven.cveCount());
    }

    @Test
    void blankBodyThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> ShodanResponseParser.parse(""));
        assertThrows(IntelSourceException.class, () -> ShodanResponseParser.parse("   "));
        assertThrows(IntelSourceException.class, () -> ShodanResponseParser.parse(null));
    }

    @Test
    void nonJsonBodyThrowsIntelSourceExceptionWithCausePreserved() {
        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> ShodanResponseParser.parse("not json"));
        assertTrue(e.getCause() != null);
    }

    @Test
    void jsonArrayRootThrowsIntelSourceException() {
        assertThrows(IntelSourceException.class, () -> ShodanResponseParser.parse("[1,2]"));
    }

    @Test
    void emptyJsonObjectYieldsHasHostDataFalse() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-empty-object.json"));
        assertFalse(report.hasHostData());
    }

    @Test
    void retypedOptionalFieldsDoNotThrow() {
        String body = "{\"ports\": \"80\", \"org\": 42, \"data\": {}}";
        assertDoesNotThrow(() -> ShodanResponseParser.parse(body));
    }

    // ---- attributes ----

    @Test
    void portsAttributeIsJoinedAscendingAndPortCountMatchesTheUntruncatedCount() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        assertEquals("22, 80, 443", report.attributes().get("ports"));
        assertEquals("3", report.attributes().get("port_count"));
    }

    @Test
    void portsFallBackToDistinctBannerPortsWhenTopLevelPortsIsAbsent() throws Exception {
        String body = "{\"ip_str\":\"198.51.100.31\","
                + "\"data\":[{\"port\":443},{\"port\":80},{\"port\":80}]}";

        ShodanHostReport report = ShodanResponseParser.parse(body);

        assertEquals("80, 443", report.attributes().get("ports"));
        assertEquals("2", report.attributes().get("port_count"));
    }

    @Test
    void servicesAttributeSummarizesPortTransportProductVersion() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        String services = report.attributes().get("services");
        assertTrue(services.contains("22/tcp OpenSSH 7.4"));
        assertTrue(services.contains("80/tcp Apache httpd 2.4.29"));
        assertTrue(services.contains("443/tcp Apache httpd 2.4.29"));
    }

    @Test
    void hostnamesOrgIspAsnOsCountryTagsAreSurfacedWhenPresent() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        assertTrue(report.attributes().get("hostnames").contains("www.example-host.test"));
        assertEquals("Example Hosting LLC", report.attributes().get("org"));
        assertEquals("Example Hosting LLC", report.attributes().get("isp"));
        assertEquals("AS64500", report.attributes().get("asn"));
        assertEquals("Linux 3.x", report.attributes().get("os"));
        assertEquals("United States", report.attributes().get("country"));
        assertTrue(report.attributes().get("tags").contains("cloud"));
    }

    @Test
    void absentFieldsAreOmittedNotBlank() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-minimal.json"));

        for (String value : report.attributes().values()) {
            assertFalse(value.isBlank());
            assertFalse(value.equals("n/a"));
        }
        assertFalse(report.attributes().containsKey("hostnames"));
        assertFalse(report.attributes().containsKey("org"));
        assertFalse(report.attributes().containsKey("isp"));
        assertFalse(report.attributes().containsKey("asn"));
        assertFalse(report.attributes().containsKey("os"));
        assertFalse(report.attributes().containsKey("country"));
        assertFalse(report.attributes().containsKey("tags"));
        assertFalse(report.attributes().containsKey("services"));
        assertFalse(report.attributes().containsKey("vuln_count"));
        assertFalse(report.attributes().containsKey("verified_vulns"));
    }

    @Test
    void vulnCountAndVerifiedVulnsAreOmittedWhenZero() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-no-vulns.json"));

        assertFalse(report.attributes().containsKey("vuln_count"));
        assertFalse(report.attributes().containsKey("verified_vulns"));
    }

    @Test
    void attributeCountNeverExceedsTwelveForAnyFixture() throws Exception {
        for (String fixture : allHostFixtures()) {
            ShodanHostReport report = ShodanResponseParser.parse(Fixtures.read("shodan", fixture));
            assertTrue(report.attributes().size() <= 12,
                    fixture + " had " + report.attributes().size() + " attributes");
        }
    }

    @Test
    void everyAttributeKeyIsDrawnFromTheFixedVocabulary() throws Exception {
        for (String fixture : allHostFixtures()) {
            ShodanHostReport report = ShodanResponseParser.parse(Fixtures.read("shodan", fixture));
            for (String key : report.attributes().keySet()) {
                assertTrue(ATTRIBUTE_VOCABULARY.contains(key),
                        fixture + " had unexpected attribute key: " + key);
            }
        }
    }

    @Test
    void everyAttributeValueIsAtMostMaxAttributeValueLength() throws Exception {
        for (String fixture : allHostFixtures()) {
            ShodanHostReport report = ShodanResponseParser.parse(Fixtures.read("shodan", fixture));
            for (String value : report.attributes().values()) {
                assertTrue(value.length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH,
                        fixture + " had an oversized attribute value");
            }
        }
    }

    @Test
    void oversizedPortsAndServicesAreTruncatedWithEllipsis() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-many-vulns.json"));

        String ports = report.attributes().get("ports");
        String services = report.attributes().get("services");
        assertTrue(ports.endsWith("…"));
        assertTrue(services.endsWith("…"));
        assertEquals("40", report.attributes().get("port_count"));
        assertEquals(100, new ShodanHostReport(true, report.cveIds(), report.verifiedVulnCount(),
                report.attributes()).score());
    }

    // ---- exclusion pins ----

    @Test
    void rawBannerTextIsNeverSurfacedInAnyAttribute() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        for (String key : report.attributes().keySet()) {
            assertFalse(key.contains("SHODAN-FIXTURE-TOKEN"));
        }
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("SHODAN-FIXTURE-TOKEN"));
        }
    }

    @Test
    void httpHtmlBodyIsNeverSurfaced() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        for (String value : report.attributes().values()) {
            assertFalse(value.contains("SHODAN-FIXTURE-TOKEN-HTML"));
        }
    }

    @Test
    void lastUpdateAndBannerTimestampsAreNeverSurfaced() throws Exception {
        ShodanHostReport report = ShodanResponseParser.parse(
                Fixtures.read("shodan", "host-with-vulns.json"));

        for (String key : report.attributes().keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("update"));
            assertFalse(lower.contains("time"));
            assertFalse(lower.contains("stamp"));
        }
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("2026-08-01T12:00:00.000000"));
        }
    }

    @Test
    void domainsFieldIsNeverSurfaced() throws Exception {
        // Use a domains value distinct from anything in hostnames, so this test cannot
        // pass merely because host-with-vulns.json's hostnames happen to be superstrings
        // of its domains entry -- it must actually pin that `domains` is never read.
        String body = "{\"ip_str\":\"198.51.100.41\",\"ports\":[80],"
                + "\"hostnames\":[\"unrelated-host.test\"],"
                + "\"domains\":[\"SHODAN-FIXTURE-DOMAIN-TOKEN.test\"]}";

        ShodanHostReport report = ShodanResponseParser.parse(body);

        assertFalse(report.attributes().containsKey("domains"));
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("SHODAN-FIXTURE-DOMAIN-TOKEN"));
        }
    }

    @Test
    void geoDetailBelowCountryIsNeverSurfaced() throws Exception {
        String body = "{\"ip_str\":\"198.51.100.32\",\"ports\":[80],"
                + "\"city\":\"Testville\",\"latitude\":1.23,\"longitude\":4.56,"
                + "\"postal_code\":\"00000\"}";

        ShodanHostReport report = ShodanResponseParser.parse(body);

        assertFalse(report.attributes().containsKey("city"));
        assertFalse(report.attributes().containsKey("latitude"));
        assertFalse(report.attributes().containsKey("longitude"));
        assertFalse(report.attributes().containsKey("postal_code"));
        for (String value : report.attributes().values()) {
            assertFalse(value.contains("Testville"));
        }
    }

    private static List<String> allHostFixtures() {
        return List.of(
                "host-with-vulns.json", "host-no-vulns.json", "host-banner-vulns-only.json",
                "host-overlapping-vulns.json", "host-many-vulns.json", "host-minimal.json",
                "host-empty-object.json");
    }
}
