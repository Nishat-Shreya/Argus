package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainName} is the single source of truth for "is this a well-formed domain" — the
 * invariant-8 helper {@code com.argus.ui} calls before dispatching to {@code core}, and the same
 * rules screen discovered CT-log names in {@link SubdomainNormalizer}. Basic LDH validation, not
 * RFC 1035-complete (plan §3.1/§7.5): no IDN conversion, no public-suffix list.
 */
class DomainNameTest {

    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals("example.com", DomainName.normalize("  Example.COM  "));
    }

    @Test
    void stripsSingleTrailingRootDot() {
        assertEquals("example.com", DomainName.normalize("example.com."));
    }

    @Test
    void acceptsMultiLabelAndHyphenatedNames() {
        assertEquals("api-v2.staging.example.co.uk",
                DomainName.normalize("api-v2.staging.example.co.uk"));
    }

    @Test
    void acceptsPunycodeLabels() {
        assertEquals("xn--s28h.example.com", DomainName.normalize("xn--s28h.example.com"));
    }

    @Test
    void rejectsNullBlankAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("   "));
    }

    @Test
    void rejectsSingleLabel() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("example"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("localhost"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("com"));
    }

    @Test
    void rejectsEmptyLabelsAndLeadingDot() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("example..com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize(".example.com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("example..com."));
    }

    @Test
    void rejectsLeadingOrTrailingHyphenInLabel() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("-example.com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("example-.com"));
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("exa mple.com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("exam_ple.com"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("user@example.com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("*.example.com"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("ex%ample.com"));
    }

    @Test
    void rejectsUrlsAndPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("https://example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("example.com/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("example.com:443"));
    }

    @Test
    void rejectsNonAsciiUnicode() {
        IllegalArgumentException cafe = assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("café.com"));
        assertTrue(cafe.getMessage().toLowerCase(Locale.ROOT).contains("punycode"));

        IllegalArgumentException cyrillic = assertThrows(IllegalArgumentException.class,
                () -> DomainName.normalize("пример.рф"));
        assertTrue(cyrillic.getMessage().toLowerCase(Locale.ROOT).contains("punycode"));
    }

    @Test
    void rejectsIpv4Literal() {
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("example.123"));
    }

    @Test
    void rejectsOverlongNameAndLabel() {
        String overlongName = "a".repeat(50) + "." + "b".repeat(50) + "." + "c".repeat(50)
                + "." + "d".repeat(50) + "." + "e".repeat(50) + ".com";
        assertTrue(overlongName.length() > DomainName.MAX_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize(overlongName));

        String overlongLabel = "a".repeat(64) + ".com";
        assertThrows(IllegalArgumentException.class, () -> DomainName.normalize(overlongLabel));

        String maxLabel = "a".repeat(63) + ".com";
        assertEquals(maxLabel, DomainName.normalize(maxLabel));
    }

    @Test
    void isWithinMatchesApexAndSubdomainsButNotSuffixTricks() {
        assertTrue(DomainName.isWithin("example.com", "example.com"));
        assertTrue(DomainName.isWithin("a.b.example.com", "example.com"));
        assertFalse(DomainName.isWithin("notexample.com", "example.com"));
        assertFalse(DomainName.isWithin("example.com.evil.test", "example.com"));
    }

    @Test
    void isValidIsTheNonThrowingForm() {
        assertTrue(DomainName.isValid("example.com"));
        assertFalse(DomainName.isValid("not a domain"));
        assertFalse(DomainName.isValid(null));
    }
}
