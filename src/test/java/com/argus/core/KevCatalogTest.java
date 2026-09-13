package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link KevCatalog} — the immutable index (plan §3.2/§6.2). C3 is the P2-03/P2-05 dedup-bug
 * regression test: the index must be keyed on canonical {@link CveId}, never on a raw string.
 */
class KevCatalogTest {

    private static final CveId LOG4SHELL = new CveId("CVE-2021-44228");
    private static final CveId ZEROLOGON = new CveId("CVE-2020-1472");
    private static final CveId FOLLINA = new CveId("CVE-2022-30190");

    private static KevEntry entry(CveId id, boolean ransomware) {
        return new KevEntry(id, "Vendor", "Product", "Name", ransomware);
    }

    // ---- C1 ----

    @Test
    void findReturnsTheEntryForAPresentCveIdAndContainsAgrees() {
        KevEntry log4shell = entry(LOG4SHELL, true);
        KevCatalog catalog = new KevCatalog("2026.09.11", List.of(log4shell));

        assertEquals(Optional.of(log4shell), catalog.find(LOG4SHELL));
        assertTrue(catalog.contains(LOG4SHELL));
    }

    // ---- C2 ----

    @Test
    void findOnAbsentIdReturnsEmptyNoException() {
        KevCatalog catalog = new KevCatalog("2026.09.11", List.of(entry(LOG4SHELL, false)));

        assertEquals(Optional.empty(), catalog.find(ZEROLOGON));
        assertFalse(catalog.contains(ZEROLOGON));
    }

    // ---- C3: the P2-03/P2-05 dedup-bug regression ----

    @Test
    void canonicalKeyingLowercaseFeedEntryFoundByUppercaseQuery() {
        CveId fromFeed = new CveId("cve-2021-44228");
        KevCatalog catalog = new KevCatalog("2026.09.11", List.of(entry(fromFeed, false)));

        assertTrue(catalog.contains(new CveId("CVE-2021-44228")));
    }

    @Test
    void canonicalKeyingUppercaseFeedEntryFoundByLowercaseQuery() {
        CveId fromFeed = new CveId("CVE-2021-44228");
        KevCatalog catalog = new KevCatalog("2026.09.11", List.of(entry(fromFeed, false)));

        assertTrue(catalog.contains(new CveId("cve-2021-44228")));
    }

    // ---- C4 ----

    @Test
    void emptyCatalogSizeZeroEveryLookupMissesVersionIsEmptyString() {
        KevCatalog catalog = KevCatalog.empty();

        assertEquals(0, catalog.size());
        assertTrue(catalog.isEmpty());
        assertFalse(catalog.contains(LOG4SHELL));
        assertEquals(Optional.empty(), catalog.find(LOG4SHELL));
        assertEquals("", catalog.catalogVersion());
    }

    // ---- C5 ----

    @Test
    void duplicateCveIdInInputLastWinsNoException() {
        KevEntry first = new KevEntry(LOG4SHELL, "Apache", "Log4j2", "first listing", false);
        KevEntry second = new KevEntry(LOG4SHELL, "Apache", "Log4j2", "re-listed", true);

        KevCatalog catalog = new KevCatalog("2026.09.11", List.of(first, second));

        assertEquals(1, catalog.size());
        assertEquals(Optional.of(second), catalog.find(LOG4SHELL));
    }

    // ---- C6 ----

    @Test
    void entriesIsSortedByCveIdAndIsADefensiveCopy() {
        KevCatalog catalog = new KevCatalog("2026.09.11",
                List.of(entry(FOLLINA, false), entry(LOG4SHELL, true), entry(ZEROLOGON, true)));

        List<KevEntry> entries = catalog.entries();
        assertEquals(List.of(ZEROLOGON, LOG4SHELL, FOLLINA),
                entries.stream().map(KevEntry::cveId).toList());

        assertThrows(UnsupportedOperationException.class,
                () -> entries.add(entry(LOG4SHELL, false)));
    }

    // ---- C7 ----

    @Test
    void nullVersionThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new KevCatalog(null, List.of()));
    }

    @Test
    void nullCollectionThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new KevCatalog("2026.09.11", null));
    }

    @Test
    void nullElementThrowsNpe() {
        List<KevEntry> withNull = new ArrayList<>();
        withNull.add(entry(LOG4SHELL, false));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new KevCatalog("2026.09.11", withNull));
    }

    // ---- C8 ----

    @Test
    void catalogVersionReturnsVerbatimUnparsed() {
        KevCatalog catalog = new KevCatalog("2026.09.11", List.of());
        assertEquals("2026.09.11", catalog.catalogVersion());
    }

    // ---- C9: no-clock pin ----

    @Test
    void reflectiveNoClockPin() {
        for (Method method : KevCatalog.class.getMethods()) {
            if (method.getDeclaringClass() != KevCatalog.class) {
                continue;
            }
            assertFalse(method.getReturnType().getPackageName().startsWith("java.time"),
                    "method " + method + " returns a java.time type");
            assertFalse(
                    Arrays.stream(method.getParameterTypes())
                            .anyMatch(t -> t.getPackageName().startsWith("java.time")),
                    "method " + method + " takes a java.time parameter");
        }
    }
}
