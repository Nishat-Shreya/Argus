package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link IntelSubject} — the validated, normalized enrichment subject (plan §3.4/§6.4).
 */
class IntelSubjectTest {

    @Test
    void domainFactoryNormalizes() {
        IntelSubject subject = IntelSubject.domain("  Example.COM. ");
        assertEquals(new IntelSubject(IntelSubjectKind.DOMAIN, "example.com"), subject);
    }

    @Test
    void ipFactoryNormalizes() {
        assertEquals(new IntelSubject(IntelSubjectKind.IP, "8.8.8.8"),
                IntelSubject.ip(" 8.8.8.8 "));
        assertEquals(new IntelSubject(IntelSubjectKind.IP, "fe80::1"),
                IntelSubject.ip("FE80::1"));
    }

    @Test
    void domainFactoryRejectsIpLiterals() {
        assertThrows(IllegalArgumentException.class, () -> IntelSubject.domain("1.2.3.4"));
    }

    @Test
    void ipFactoryRejectsHostnames() {
        assertThrows(IllegalArgumentException.class, () -> IntelSubject.ip("example.com"));
    }

    @Test
    void constructorRejectsKindValueMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSubject(IntelSubjectKind.IP, "example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSubject(IntelSubjectKind.DOMAIN, "1.2.3.4"));
    }

    @Test
    void equalityIsStructural() {
        IntelSubject a = IntelSubject.domain("example.com");
        IntelSubject b = IntelSubject.domain("example.com");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(1, new HashSet<>(List.of(a, b)).size());
        assertNotEquals(IntelSubject.domain("example.com"), IntelSubject.domain("example.org"));
        assertNotEquals(IntelSubject.ip("8.8.8.8"), IntelSubject.domain("example.com"));
    }

    @Test
    void rejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new IntelSubject(null, "example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSubject(IntelSubjectKind.DOMAIN, null));
        assertThrows(IllegalArgumentException.class, () -> IntelSubject.domain(null));
        assertThrows(IllegalArgumentException.class, () -> IntelSubject.ip(null));
    }
}
