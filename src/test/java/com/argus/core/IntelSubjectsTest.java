package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests W1-W4 of the P2-06 plan (§7.5). */
class IntelSubjectsTest {

    @Test
    void wildcardSubdomainYieldsEmptyNotTheBaseZone() {
        Optional<IntelSubject> subject = IntelSubjects.forSubdomain(new Subdomain("*.example.com"));

        assertEquals(Optional.empty(), subject);
    }

    @Test
    void ordinarySubdomainYieldsADomainSubject() {
        Optional<IntelSubject> subject = IntelSubjects.forSubdomain(new Subdomain("www.example.com"));

        assertTrue(subject.isPresent());
        assertEquals(IntelSubjectKind.DOMAIN, subject.get().kind());
        assertEquals("www.example.com", subject.get().value());
    }

    @Test
    void nullSubdomainThrows() {
        assertThrows(NullPointerException.class, () -> IntelSubjects.forSubdomain(null));
    }

    @Test
    void crtShShapedListWithWildcardsMapsWithNoException() {
        List<Subdomain> subdomains = List.of(
                new Subdomain("www.example.com"),
                new Subdomain("*.example.com"),
                new Subdomain("mail.example.com"),
                new Subdomain("*.dev.example.com"));

        List<IntelSubject> subjects = subdomains.stream()
                .map(IntelSubjects::forSubdomain)
                .flatMap(Optional::stream)
                .toList();

        assertEquals(2, subjects.size());
    }
}
