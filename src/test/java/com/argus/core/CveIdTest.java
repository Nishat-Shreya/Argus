package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link CveId} — canonical-case CVE identifier value type (plan §3.5/§6.5). */
class CveIdTest {

    private static final List<String> MALFORMED = List.of(
            "2021-44228", "CVE-21-44228", "CVE-2021-123", "CVE-2021-", "CVE_2021_44228", "");

    @Test
    void uppercasesAndTrims() {
        assertEquals("CVE-2021-44228", new CveId(" cve-2021-44228 ").id());
    }

    @Test
    void acceptsLongSequenceNumbers() {
        assertEquals("CVE-2023-1234567", new CveId("CVE-2023-1234567").id());
    }

    @Test
    void rejectsMalformed() {
        for (String bad : MALFORMED) {
            assertThrows(IllegalArgumentException.class, () -> new CveId(bad),
                    "expected rejection for: " + bad);
        }
        assertThrows(NullPointerException.class, () -> new CveId(null));
    }

    @Test
    void equalityIsStructuralAcrossCase() {
        CveId lower = new CveId("cve-2021-44228");
        CveId upper = new CveId("CVE-2021-44228");
        assertEquals(lower, upper);
        assertEquals(1, new HashSet<>(List.of(lower, upper)).size());
    }

    @Test
    void yearIsExtracted() {
        assertEquals(2021, new CveId("CVE-2021-44228").year());
    }

    @Test
    void isValidIsTheNonThrowingTwin() {
        assertTrue(CveId.isValid(" cve-2021-44228 "));
        assertTrue(CveId.isValid("CVE-2023-1234567"));
        for (String bad : MALFORMED) {
            assertFalse(CveId.isValid(bad), "expected invalid for: " + bad);
        }
        assertFalse(CveId.isValid(null));
    }
}
