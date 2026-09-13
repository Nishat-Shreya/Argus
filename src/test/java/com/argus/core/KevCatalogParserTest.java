package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link KevCatalogParser} — Jackson tree-model parse of the KEV feed, tolerant of unknown
 * fields including the live feed's undocumented {@code forensicTriage} (plan §3.7/§6.4).
 */
class KevCatalogParserTest {

    // ---- P1 ----

    @Test
    void happyPathCatalogSmallFourEntries() throws Exception {
        KevCatalog catalog = KevCatalogParser.parse(Fixtures.read("kev", "catalog-small.json"));

        assertEquals(4, catalog.size());
        assertEquals("2026.09.11", catalog.catalogVersion());

        Optional<KevEntry> log4shell = catalog.find(new CveId("CVE-2021-44228"));
        assertTrue(log4shell.isPresent());
        assertEquals("Apache", log4shell.get().vendorProject());
        assertEquals("Log4j2", log4shell.get().product());
        assertEquals("Apache Log4j2 Remote Code Execution Vulnerability",
                log4shell.get().vulnerabilityName());
        assertTrue(log4shell.get().knownRansomwareCampaignUse());

        Optional<KevEntry> printNightmare = catalog.find(new CveId("CVE-2021-34527"));
        assertTrue(printNightmare.isPresent());
        assertFalse(printNightmare.get().knownRansomwareCampaignUse());
    }

    // ---- P2 ----

    @Test
    void undocumentedAndUnreadFieldsAreIgnoredWithoutError() throws Exception {
        // catalog-small.json carries forensicTriage, cwes, notes, dateAdded, dueDate,
        // dateReleased, count, title alongside the fields we read; parsing must not choke.
        KevCatalog catalog = KevCatalogParser.parse(Fixtures.read("kev", "catalog-small.json"));
        assertNotNull(catalog);
        assertEquals(4, catalog.size());
    }

    // ---- P3 ----

    @Test
    void mixedCaseAndWhitespacePaddedCveIdsCanonicalise() throws Exception {
        KevCatalog catalog =
                KevCatalogParser.parse(Fixtures.read("kev", "catalog-mixed-case-cve.json"));

        assertTrue(catalog.contains(new CveId("CVE-2021-44228")));
        assertTrue(catalog.contains(new CveId("CVE-2022-30190")));
        assertEquals(2, catalog.size());
    }

    // ---- P4 ----

    @Test
    void invalidCveIdEntriesAreDroppedTheValidOneIsKeptNoException() throws Exception {
        KevCatalog catalog =
                KevCatalogParser.parse(Fixtures.read("kev", "catalog-invalid-cve-id.json"));

        assertEquals(1, catalog.size());
        assertTrue(catalog.contains(new CveId("CVE-2021-44228")));
    }

    // ---- P5 ----

    @Test
    void entryWithOnlyCveIdParsesWithEmptyStringsAndFalseRansomware() throws Exception {
        KevCatalog catalog =
                KevCatalogParser.parse(Fixtures.read("kev", "catalog-entry-missing-fields.json"));

        KevEntry onlyCveId = catalog.find(new CveId("CVE-2021-44228")).orElseThrow();
        assertEquals("", onlyCveId.vendorProject());
        assertEquals("", onlyCveId.product());
        assertEquals("", onlyCveId.vulnerabilityName());
        assertFalse(onlyCveId.knownRansomwareCampaignUse());

        KevEntry likely = catalog.find(new CveId("CVE-2022-30190")).orElseThrow();
        assertFalse(likely.knownRansomwareCampaignUse());
    }

    // ---- P6 ----

    @Test
    void emptyVulnerabilitiesArrayIsAValidEmptyCatalogNotAnException() throws Exception {
        KevCatalog catalog =
                KevCatalogParser.parse(Fixtures.read("kev", "catalog-empty-vulnerabilities.json"));

        assertTrue(catalog.isEmpty());
        assertEquals("2026.09.11", catalog.catalogVersion());
    }

    // ---- P7 ----

    @Test
    void missingVulnerabilitiesKeyThrows() {
        String json = Fixtures.read("kev", "catalog-missing-vulnerabilities.json");
        assertThrows(KevCatalogException.class, () -> KevCatalogParser.parse(json));
    }

    // ---- P8 ----

    @Test
    void topLevelArrayThrows() {
        String json = Fixtures.read("kev", "catalog-not-an-object.json");
        assertThrows(KevCatalogException.class, () -> KevCatalogParser.parse(json));
    }

    // ---- P9 ----

    @Test
    void malformedJsonThrowsWithJacksonCausePreserved() {
        String json = Fixtures.read("kev", "catalog-malformed.json");
        KevCatalogException e =
                assertThrows(KevCatalogException.class, () -> KevCatalogParser.parse(json));
        assertNotNull(e.getCause());
    }

    // ---- P10 ----

    @Test
    void blankInputThrows() {
        assertThrows(KevCatalogException.class, () -> KevCatalogParser.parse(""));
        assertThrows(KevCatalogException.class, () -> KevCatalogParser.parse("   "));
    }

    // ---- P11 ----

    @Test
    void duplicateCveInFeedProducesOneEntryNoException() throws Exception {
        KevCatalog catalog =
                KevCatalogParser.parse(Fixtures.read("kev", "catalog-duplicate-cve.json"));

        assertEquals(1, catalog.size());
    }

    // ---- P12 ----

    @Test
    void isRansomwareKnownTable() {
        assertTrue(KevCatalogParser.isRansomwareKnown("Known"));
        assertTrue(KevCatalogParser.isRansomwareKnown("known"));
        assertTrue(KevCatalogParser.isRansomwareKnown(" Known "));
        assertFalse(KevCatalogParser.isRansomwareKnown("Unknown"));
        assertFalse(KevCatalogParser.isRansomwareKnown(""));
        assertFalse(KevCatalogParser.isRansomwareKnown(null));
        assertFalse(KevCatalogParser.isRansomwareKnown("Likely"));
    }
}
