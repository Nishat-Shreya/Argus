package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScheduledScanValidationTest {

    // --- target: delegates to DashboardValidation ------------------------------------------

    @Test
    void checkTargetAcceptsAWellFormedDomainAndNormalizesIt() {
        ScheduledScanValidation.TargetResult result =
                ScheduledScanValidation.checkTarget("Example.COM");
        assertTrue(result.valid());
        assertEquals("example.com", result.target());
    }

    @Test
    void checkTargetRejectsABlankValue() {
        assertFalse(ScheduledScanValidation.checkTarget("   ").valid());
    }

    @Test
    void checkTargetRejectsAMalformedDomain() {
        assertFalse(ScheduledScanValidation.checkTarget("not a domain").valid());
    }

    // --- interval ----------------------------------------------------------------------------

    @Test
    void checkIntervalAcceptsAPositiveWholeNumber() {
        ScheduledScanValidation.IntervalResult result = ScheduledScanValidation.checkInterval("15");
        assertTrue(result.valid());
        assertEquals(15, result.minutes());
    }

    @Test
    void checkIntervalStripsWhitespace() {
        assertTrue(ScheduledScanValidation.checkInterval("  30  ").valid());
    }

    @Test
    void checkIntervalRejectsBlank() {
        assertFalse(ScheduledScanValidation.checkInterval("").valid());
        assertFalse(ScheduledScanValidation.checkInterval(null).valid());
    }

    @Test
    void checkIntervalRejectsNonNumeric() {
        assertFalse(ScheduledScanValidation.checkInterval("soon").valid());
    }

    @Test
    void checkIntervalRejectsZeroAndNegative() {
        assertFalse(ScheduledScanValidation.checkInterval("0").valid());
        assertFalse(ScheduledScanValidation.checkInterval("-5").valid());
    }

    @Test
    void checkIntervalRejectsOverTheMaximum() {
        assertFalse(ScheduledScanValidation.checkInterval("10081").valid());
    }

    @Test
    void checkIntervalAcceptsExactlyTheMaximum() {
        assertTrue(ScheduledScanValidation.checkInterval("10080").valid());
    }
}
