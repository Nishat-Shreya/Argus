package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests O1-O7 of the P2-06 plan (§7.2). */
class IntelSourceOutcomeTest {

    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");

    private static IntelResult okResult() {
        return IntelResult.unknown("virustotal", A_DOMAIN);
    }

    @Test
    void okFactoryProducesOkOutcome() {
        IntelResult result = okResult();
        IntelSourceOutcome outcome = IntelSourceOutcome.ok("virustotal", result);

        assertEquals(IntelSourceStatus.OK, outcome.status());
        assertSame(result, outcome.result());
        assertNull(outcome.error());
        assertTrue(outcome.isOk());
    }

    @Test
    void unsupportedFactoryHasNoNullableComponents() {
        IntelSourceOutcome outcome = IntelSourceOutcome.unsupported("shodan");

        assertEquals(IntelSourceStatus.UNSUPPORTED_SUBJECT, outcome.status());
        assertNull(outcome.result());
        assertNull(outcome.error());
        assertFalse(outcome.isOk());
    }

    @Test
    void notConfiguredFactoryRetainsMissingApiKeyException() {
        MissingApiKeyException e = new MissingApiKeyException("censys", "apikey.censys");
        IntelSourceOutcome outcome = IntelSourceOutcome.notConfigured("censys", e);

        assertEquals(IntelSourceStatus.NOT_CONFIGURED, outcome.status());
        assertSame(e, outcome.error());
        assertEquals("apikey.censys", ((MissingApiKeyException) outcome.error()).vaultEntryName());
        assertNull(outcome.result());
        assertFalse(outcome.isOk());
    }

    @Test
    void failedFactoryMirrorsStatusCode() {
        IntelSourceException withStatus = new IntelSourceException("abuseipdb", "rate limited", 429);
        IntelSourceOutcome withStatusOutcome = IntelSourceOutcome.failed("abuseipdb", withStatus);
        assertEquals(IntelSourceStatus.FAILED, withStatusOutcome.status());
        assertEquals(429, withStatusOutcome.statusCode());

        IntelSourceException withoutStatus = new IntelSourceException("abuseipdb", "transport error");
        IntelSourceOutcome withoutStatusOutcome = IntelSourceOutcome.failed("abuseipdb", withoutStatus);
        assertEquals(0, withoutStatusOutcome.statusCode());
    }

    @Test
    void okWithNullResultThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome("virustotal", IntelSourceStatus.OK, null, null));
    }

    @Test
    void failedWithNullErrorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome("virustotal", IntelSourceStatus.FAILED, null, null));
    }

    @Test
    void unsupportedWithNonNullResultThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome(
                        "virustotal", IntelSourceStatus.UNSUPPORTED_SUBJECT, okResult(), null));
    }

    @Test
    void okWithNonNullErrorThrows() {
        IntelSourceException e = new IntelSourceException("virustotal", "boom");
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome("virustotal", IntelSourceStatus.OK, okResult(), e));
    }

    @Test
    void nullSourceNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome(
                        null, IntelSourceStatus.UNSUPPORTED_SUBJECT, null, null));
    }

    @Test
    void blankSourceNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntelSourceOutcome(
                        "   ", IntelSourceStatus.UNSUPPORTED_SUBJECT, null, null));
    }

    @Test
    void statusCodeIsZeroForNonFailedStatuses() {
        assertEquals(0, IntelSourceOutcome.ok("virustotal", okResult()).statusCode());
        assertEquals(0, IntelSourceOutcome.unsupported("shodan").statusCode());
        assertEquals(0, IntelSourceOutcome
                .notConfigured("censys", new MissingApiKeyException("censys", "apikey.censys"))
                .statusCode());
    }
}
