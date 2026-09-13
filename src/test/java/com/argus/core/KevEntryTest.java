package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link KevEntry} — one catalog row, date-free by construction (plan §3.1/§6.1). E5 is the
 * item's single most important test: it pins the no-second-clock invariant structurally, not
 * by discipline.
 */
class KevEntryTest {

    private static final CveId LOG4SHELL = new CveId("CVE-2021-44228");

    // ---- E1 ----

    @Test
    void happyPathAllFiveComponentsRoundTrip() {
        KevEntry entry = new KevEntry(LOG4SHELL, "Apache", "Log4j2", "Log4j2 RCE", true);

        assertEquals(LOG4SHELL, entry.cveId());
        assertEquals("Apache", entry.vendorProject());
        assertEquals("Log4j2", entry.product());
        assertEquals("Log4j2 RCE", entry.vulnerabilityName());
        assertTrue(entry.knownRansomwareCampaignUse());
    }

    // ---- E2 ----

    @Test
    void nullCveIdThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new KevEntry(null, "Apache", "Log4j2", "Log4j2 RCE", true));
    }

    // ---- E3 ----

    @Test
    void nullOrBlankStringsBecomeEmptyNeverNullNeverNotApplicable() {
        KevEntry entry = new KevEntry(LOG4SHELL, null, "   ", "", true);

        assertEquals("", entry.vendorProject());
        assertEquals("", entry.product());
        assertEquals("", entry.vulnerabilityName());
    }

    // ---- E4 ----

    @Test
    void longVulnerabilityNameIsTruncatedToMaxTextLength() {
        String tooLong = "x".repeat(KevEntry.MAX_TEXT_LENGTH + 50);

        KevEntry entry = new KevEntry(LOG4SHELL, "Apache", "Log4j2", tooLong, false);

        assertEquals(KevEntry.MAX_TEXT_LENGTH, entry.vulnerabilityName().length());
    }

    // ---- E5: the no-date pin ----

    @Test
    void reflectiveNoDatePin() {
        RecordComponent[] components = KevEntry.class.getRecordComponents();
        assertEquals(5, components.length,
                "KevEntry must have exactly 5 record components");

        Pattern forbiddenName =
                Pattern.compile("(?i).*(date|due|added|time|instant|released).*");

        for (RecordComponent component : components) {
            String packageName = component.getType().getPackageName();
            assertFalse(packageName.startsWith("java.time"),
                    "component " + component.getName() + " must not be a java.time type");
            assertFalse(forbiddenName.matcher(component.getName()).matches(),
                    "component name looks date-shaped: " + component.getName());
        }
    }

    // ---- E6 ----

    @Test
    void equalComponentsMeansEquals() {
        KevEntry a = new KevEntry(LOG4SHELL, "Apache", "Log4j2", "Log4j2 RCE", true);
        KevEntry b = new KevEntry(LOG4SHELL, "Apache", "Log4j2", "Log4j2 RCE", true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
