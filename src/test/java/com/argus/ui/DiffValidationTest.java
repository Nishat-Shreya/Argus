package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanSummary;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Section 7.8: {@code DiffValidation} — invariant 8 for this screen (plan §3.5, §4.3). */
class DiffValidationTest {

    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T01:00:00Z");

    private static ScanSummary scan(long id, String target, Instant startedAt) {
        return new ScanSummary(id, target, startedAt, startedAt.plusSeconds(300), "COMPLETED");
    }

    @Test
    void nullBaselineIsInvalid() {
        DiffValidation.Result result =
                DiffValidation.check(null, scan(1L, "example.com", EARLIER));
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("baseline"));
    }

    @Test
    void nullCurrentIsInvalid() {
        DiffValidation.Result result =
                DiffValidation.check(scan(1L, "example.com", EARLIER), null);
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("current"));
    }

    @Test
    void nullBaselineAndNullCurrentMessagesAreDistinct() {
        DiffValidation.Result baselineMissing =
                DiffValidation.check(null, scan(1L, "example.com", EARLIER));
        DiffValidation.Result currentMissing =
                DiffValidation.check(scan(1L, "example.com", EARLIER), null);
        assertNotEquals(baselineMissing.message(), currentMissing.message());
    }

    @Test
    void sameIdTwiceIsInvalid() {
        ScanSummary scan = scan(1L, "example.com", EARLIER);
        DiffValidation.Result result = DiffValidation.check(scan, scan);
        assertFalse(result.valid());
    }

    @Test
    void differentTargetsAreInvalid() {
        DiffValidation.Result result = DiffValidation.check(
                scan(1L, "example.com", EARLIER), scan(2L, "other.test", LATER));
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("target"));
    }

    @Test
    void baselineLaterThanCurrentIsInvalid() {
        DiffValidation.Result result = DiffValidation.check(
                scan(1L, "example.com", LATER), scan(2L, "example.com", EARLIER));
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("earlier"));
    }

    @Test
    void equalStartedAtDifferentIdsSameTargetIsValid() {
        DiffValidation.Result result = DiffValidation.check(
                scan(1L, "example.com", EARLIER), scan(2L, "example.com", EARLIER));
        assertTrue(result.valid());
    }

    @Test
    void aWellFormedPairIsValidWithEmptyMessage() {
        DiffValidation.Result result = DiffValidation.check(
                scan(1L, "example.com", EARLIER), scan(2L, "example.com", LATER));
        assertTrue(result.valid());
        assertNull(result.message());
    }

    @Test
    void resultHasExactlyTwoComponents() {
        assertEquals(2, DiffValidation.Result.class.getRecordComponents().length);
    }
}
