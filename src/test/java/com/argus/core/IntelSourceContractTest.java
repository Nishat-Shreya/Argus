package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@link IntelSource} contract, exercised via {@link FakeIntelSource} — zero HTTP, zero
 * vault (plan §6.7).
 */
class IntelSourceContractTest {

    private static final IntelSubject AN_IP = IntelSubject.ip("1.2.3.4");
    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");

    private FakeIntelSource ipOnlyFake() {
        return new FakeIntelSource("fake", Set.of(IntelSubjectKind.IP));
    }

    @Test
    void supportsDelegatesToSupportedSubjects() {
        FakeIntelSource fake = ipOnlyFake();
        assertTrue(fake.supports(AN_IP));
        assertFalse(fake.supports(A_DOMAIN));
    }

    @Test
    void unsupportedSubjectThrowsBeforeAnyWork() {
        FakeIntelSource fake = ipOnlyFake();
        assertThrows(IllegalArgumentException.class, () -> fake.query(A_DOMAIN));
        assertEquals(0, fake.queryCount());
    }

    @Test
    void nullSubjectThrows() {
        FakeIntelSource fake = ipOnlyFake();
        assertThrows(RuntimeException.class, () -> fake.query(null));
        assertEquals(0, fake.queryCount());
    }

    @Test
    void supportedSubjectsIsNonEmptyAndUnmodifiable() {
        FakeIntelSource fake = ipOnlyFake();
        assertFalse(fake.supportedSubjects().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> fake.supportedSubjects().add(IntelSubjectKind.DOMAIN));
    }

    @Test
    void scriptedFailurePropagatesAsIntelSourceException() throws Exception {
        FakeIntelSource fake = ipOnlyFake();
        fake.willThrow(new IntelSourceException("fake", "rate limited", 429));

        IntelSourceException e =
                assertThrows(IntelSourceException.class, () -> fake.query(AN_IP));
        assertEquals(429, e.statusCode());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped() {
        FakeIntelSource fake = ipOnlyFake();
        fake.willThrow(new InterruptedException("cancelled"));

        assertThrows(InterruptedException.class, () -> fake.query(AN_IP));
    }

    @Test
    void missingApiKeyIsDistinguishableAndNamesOnlyTheEntry() throws Exception {
        FakeIntelSource fake = ipOnlyFake();
        fake.willThrow(new MissingApiKeyException("fake", "apikey.fake"));

        IntelSourceException e =
                assertThrows(IntelSourceException.class, () -> fake.query(AN_IP));
        assertTrue(e instanceof MissingApiKeyException);
        assertTrue(e.getMessage().contains("apikey.fake"));
        assertFalse(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("secret"));
    }
}
