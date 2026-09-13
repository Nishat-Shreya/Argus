package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AbuseCategories} — the 23-entry category code-to-name table, in isolation (plan
 * §3.8/§6.1). Data, not logic; kept out of the parser so the parser's tests are not also
 * table tests.
 */
class AbuseCategoriesTest {

    @Test
    void namedCodesMatchTheDocumentedTable() {
        assertEquals("SSH", AbuseCategories.nameOf(22));
        assertEquals("Brute-Force", AbuseCategories.nameOf(18));
        assertEquals("Port Scan", AbuseCategories.nameOf(14));
        assertEquals("DNS Compromise", AbuseCategories.nameOf(1));
        assertEquals("IoT Targeted", AbuseCategories.nameOf(23));
    }

    @Test
    void everyDocumentedCodeOneToTwentyThreeReturnsANonBlankRealName() {
        for (int code = 1; code <= 23; code++) {
            String name = AbuseCategories.nameOf(code);
            assertFalse(name.isBlank(), "code " + code + " returned a blank name");
            assertNotEquals("Category " + code, name,
                    "code " + code + " fell back instead of using the documented name");
        }
    }

    @Test
    void unknownCodeDegradesToTheFallbackFormRatherThanThrowingOrVanishing() {
        assertEquals("Category 99", AbuseCategories.nameOf(99));
    }

    @Test
    void outOfRangeCodesReturnTheFallbackFormWithoutThrowing() {
        assertEquals("Category 0", assertDoesNotThrow(() -> AbuseCategories.nameOf(0)));
        assertEquals("Category -1", assertDoesNotThrow(() -> AbuseCategories.nameOf(-1)));
        assertTrue(true);
    }
}
