package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * {@link CensysRiskSeverity} — the tolerant vocabulary mapping in isolation (plan §3.8/§6.1).
 * No HTTP, no vault, no JSON.
 */
class CensysRiskSeverityTest {

    @Test
    void ofMapsEveryDocumentedToken() {
        assertEquals(CensysRiskSeverity.CRITICAL, CensysRiskSeverity.of("critical"));
        assertEquals(CensysRiskSeverity.HIGH, CensysRiskSeverity.of("high"));
        assertEquals(CensysRiskSeverity.MEDIUM, CensysRiskSeverity.of("medium"));
        assertEquals(CensysRiskSeverity.LOW, CensysRiskSeverity.of("low"));
    }

    @Test
    void ofIsCaseInsensitiveAndTrims() {
        assertEquals(CensysRiskSeverity.CRITICAL, CensysRiskSeverity.of(" CRITICAL "));
        assertEquals(CensysRiskSeverity.HIGH, CensysRiskSeverity.of("High"));
    }

    @Test
    void ofDegradesUnknownTokensToUnrated() {
        assertEquals(CensysRiskSeverity.UNRATED, CensysRiskSeverity.of("catastrophic"));
        assertEquals(CensysRiskSeverity.UNRATED, CensysRiskSeverity.of(""));
        assertEquals(CensysRiskSeverity.UNRATED,
                assertDoesNotThrow(() -> CensysRiskSeverity.of(null)));
    }

    @Test
    void declarationOrderIsCriticalFirst() {
        CensysRiskSeverity[] values = CensysRiskSeverity.values();
        assertEquals(CensysRiskSeverity.CRITICAL, values[0]);
        assertEquals(CensysRiskSeverity.HIGH, values[1]);
        assertEquals(CensysRiskSeverity.MEDIUM, values[2]);
        assertEquals(CensysRiskSeverity.LOW, values[3]);
        assertEquals(CensysRiskSeverity.UNRATED, values[4]);
    }

    @Test
    void tokenRoundTripsForEveryRatedConstant() {
        assertEquals("critical", CensysRiskSeverity.CRITICAL.token());
        assertEquals("high", CensysRiskSeverity.HIGH.token());
        assertEquals("medium", CensysRiskSeverity.MEDIUM.token());
        assertEquals("low", CensysRiskSeverity.LOW.token());
        assertEquals("", CensysRiskSeverity.UNRATED.token());
        for (CensysRiskSeverity severity : CensysRiskSeverity.values()) {
            if (severity == CensysRiskSeverity.UNRATED) {
                continue;
            }
            assertEquals(severity, CensysRiskSeverity.of(severity.token()));
        }
    }
}
