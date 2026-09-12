package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.DomainName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Section 6.1: {@code DashboardValidation} -- the invariant-8 seam for the target field. NO
 * {@code javafx.*} imports, so it runs headless (the {@code LoginValidationTest} precedent).
 */
class DashboardValidationTest {

    @Test
    void acceptsAValidDomain() {
        DashboardValidation.Result result = DashboardValidation.check("example.com");

        assertTrue(result.valid());
        assertEquals("example.com", result.target());
    }

    @Test
    void normalizesCaseWhitespaceAndTrailingDot() {
        DashboardValidation.Result result = DashboardValidation.check("  EXAMPLE.COM.  ");

        assertTrue(result.valid());
        assertEquals("example.com", result.target());
    }

    @Test
    void rejectsNullAndBlank() {
        for (String input : new String[] {null, "", "   "}) {
            DashboardValidation.Result result = DashboardValidation.check(input);
            assertFalse(result.valid());
            assertTrue(result.message().toLowerCase().contains("target"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "com", "a..b", "-a.com", "a-.com", "café.com",
            "1.2.3.4", "a@b.com"})
    void rejectsMalformedDomains(String candidate) {
        DashboardValidation.Result result = DashboardValidation.check(candidate);
        assertFalse(result.valid(), candidate + " should have been rejected");
    }

    @Test
    void rejectsAnOverlongDomain() {
        String label = "a".repeat(60);
        String overlong = (label + ".").repeat(5) + "com";
        DashboardValidation.Result result = DashboardValidation.check(overlong);
        assertFalse(result.valid());
    }

    @Test
    void acceptsPunycode() {
        DashboardValidation.Result result = DashboardValidation.check("xn--s28h.example.com");
        assertTrue(result.valid());
    }

    @Test
    void delegatesToDomainName() {
        String[] cases = {"example.com", "sub.example.com", "xn--s28h.example.com", "a-b.com"};
        for (String candidate : cases) {
            assertTrue(DomainName.isValid(candidate), candidate + " expected valid by DomainName");
            DashboardValidation.Result result = DashboardValidation.check(candidate);
            assertTrue(result.valid(), candidate + " should be accepted here too");
        }
    }

    @Test
    void messagesAreSentenceCase() {
        DashboardValidation.Result blank = DashboardValidation.check("");
        assertTrue(Character.isUpperCase(blank.message().charAt(0)));

        DashboardValidation.Result malformed = DashboardValidation.check("localhost");
        assertTrue(Character.isUpperCase(malformed.message().charAt(0)));
    }

    @Test
    void invalidResultCarriesNoTarget() {
        DashboardValidation.Result result = DashboardValidation.check("localhost");
        assertFalse(result.valid());
        assertNull(result.target());
    }
}
