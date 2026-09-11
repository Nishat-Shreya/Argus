package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link Subdomain} is a structural value type: one field, {@code name}. Wildcard-ness is
 * derived from the name itself, not a separate boolean (plan §3.2) — this is load-bearing for
 * dedup and for {@code ScanDiffEngine} (P2-08) set equality. No timestamp, no issuer, no
 * certificate id (plan §7.3, the P1-01 carry-forward).
 */
class SubdomainTest {

    @Test
    void equalityIsStructural() {
        Subdomain a = new Subdomain("www.example.com");
        Subdomain b = new Subdomain("www.example.com");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<Subdomain> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertEquals(1, set.size());
    }

    @Test
    void wildcardIsDistinctFromApex() {
        Subdomain wildcard = new Subdomain("*.example.com");
        Subdomain apex = new Subdomain("example.com");
        assertFalse(wildcard.equals(apex));
        Set<Subdomain> set = new HashSet<>();
        set.add(wildcard);
        set.add(apex);
        assertEquals(2, set.size());
    }

    @Test
    void isWildcardAndBaseName() {
        Subdomain wildcard = new Subdomain("*.example.com");
        assertTrue(wildcard.isWildcard());
        assertEquals("example.com", wildcard.baseName());

        Subdomain plain = new Subdomain("www.example.com");
        assertFalse(plain.isWildcard());
        assertEquals("www.example.com", plain.baseName());
    }

    @Test
    void rejectsInvalidNames() {
        assertThrows(NullPointerException.class, () -> new Subdomain(null));
        assertThrows(IllegalArgumentException.class, () -> new Subdomain(""));
        assertThrows(IllegalArgumentException.class, () -> new Subdomain("Example.com"));
        assertThrows(IllegalArgumentException.class, () -> new Subdomain("*"));
        assertThrows(IllegalArgumentException.class, () -> new Subdomain("*.*.example.com"));
        assertThrows(IllegalArgumentException.class, () -> new Subdomain("user@example.com"));
    }

    @Test
    void hasNoTimestampOrIssuerComponent() {
        RecordComponent[] components = Subdomain.class.getRecordComponents();
        assertEquals(1, components.length);
        assertEquals("name", components[0].getName());
    }
}
