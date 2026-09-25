package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SubdomainNormalizer} turns raw crt.name name tokens into the canonical, scoped,
 * deduplicated result set — DNS-name concerns only, zero Jackson, zero HTTP (plan §3.4).
 */
class SubdomainNormalizerTest {

    @Test
    void lowercasesAndTrims() {
        List<Subdomain> result =
                SubdomainNormalizer.normalize(List.of(" WWW.Example.COM "), "example.com");
        assertEquals(List.of(new Subdomain("www.example.com")), result);
    }

    @Test
    void stripsTrailingRootDot() {
        List<Subdomain> result =
                SubdomainNormalizer.normalize(List.of("api.example.com."), "example.com");
        assertEquals(List.of(new Subdomain("api.example.com")), result);
    }

    @Test
    void dedupesRepeatedNames() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("www.example.com", "www.example.com", "www.example.com",
                        "www.example.com", "www.example.com"),
                "example.com");
        assertEquals(1, result.size());
    }

    @Test
    void dedupesWildcardEntriesButKeepsThemDistinctFromTheApex() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("*.example.com", "*.example.com", "example.com"), "example.com");
        assertEquals(
                List.of(new Subdomain("*.example.com"), new Subdomain("example.com")), result);
    }

    @Test
    void dropsOutOfScopeSans() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("www.example.com", "cdn.othercorp.net"), "example.com");
        assertFalse(result.contains(new Subdomain("cdn.othercorp.net")));
    }

    @Test
    void dropsSuffixConfusionTraps() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("notexample.com", "example.com.evil.test"), "example.com");
        assertTrue(result.isEmpty());
    }

    @Test
    void dropsEmailAddressesAndJunkTokens() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("admin@example.com", "", "   ", "*", "*.*.example.com"), "example.com");
        assertTrue(result.isEmpty());
    }

    @Test
    void keepsTheApex() {
        List<Subdomain> result =
                SubdomainNormalizer.normalize(List.of("example.com"), "example.com");
        assertTrue(result.contains(new Subdomain("example.com")));
    }

    @Test
    void resultIsAscendingByNameAndUnmodifiable() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("www.example.com", "*.example.com", "example.com"), "example.com");
        assertEquals("*.example.com", result.get(0).name());
        List<String> names = result.stream().map(Subdomain::name).toList();
        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names);
        assertThrows(UnsupportedOperationException.class,
                () -> result.add(new Subdomain("junk.example.com")));
    }

    @Test
    void allNamesFilteredOutYieldsEmptyList() {
        List<Subdomain> result = SubdomainNormalizer.normalize(
                List.of("notexample.com", "cdn.othercorp.net", "admin@example.com"),
                "example.com");
        assertEquals(List.of(), result);
    }

    @Test
    void normalizesTheWholeFixtureToTheExpectedSet() throws Exception {
        List<String> rawTokens = CrtNameResponseParser.parse(Fixtures.read("example-com.json"));
        List<Subdomain> result = SubdomainNormalizer.normalize(rawTokens, "example.com");

        List<Subdomain> expected = List.of(
                new Subdomain("*.example.com"),
                new Subdomain("api.example.com"),
                new Subdomain("assets.example.com"),
                new Subdomain("dev.example.com"),
                new Subdomain("example.com"),
                new Subdomain("legacy.example.com"),
                new Subdomain("mail.example.com"),
                new Subdomain("www.example.com"));
        assertEquals(expected, result);
    }
}
