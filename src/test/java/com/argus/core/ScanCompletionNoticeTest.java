package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The "a scan completed" fact the email channel sends on EVERY successful scan -- unlike
 *  {@link ScanAlert}, which exists only when a scan found something new. */
class ScanCompletionNoticeTest {

    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 2, List.of("a.example.com", "b.example.com"));

    @Test
    void c1AFirstScanHasNoBaselineAndNoNewFindings() {
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 5L, 12,
                OptionalLong.empty(), Optional.empty());

        assertEquals(0, notice.newFindingCount());
        assertTrue(notice.baselineScanId().isEmpty());
    }

    @Test
    void c2AScanWithABaselineButNothingNewIsValid() {
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 5L, 12,
                OptionalLong.of(4L), Optional.empty());

        assertEquals(0, notice.newFindingCount());
        assertEquals(4L, notice.baselineScanId().getAsLong());
    }

    @Test
    void c3NewFindingsAreCountedFromTheAlert() {
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 2L, 12,
                OptionalLong.of(1L), Optional.of(ALERT));

        assertEquals(2, notice.newFindingCount());
    }

    @Test
    void c4NewFindingsWithoutABaselineAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScanCompletionNotice(
                "example.com", 2L, 12, OptionalLong.empty(), Optional.of(ALERT)));
    }

    @Test
    void c5NegativeIdsOrCountsAndNullsAreRejected() {
        assertThrows(NullPointerException.class, () -> new ScanCompletionNotice(
                null, 1L, 0, OptionalLong.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ScanCompletionNotice(
                "example.com", -1L, 0, OptionalLong.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ScanCompletionNotice(
                "example.com", 1L, -1, OptionalLong.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ScanCompletionNotice(
                "example.com", 1L, 0, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ScanCompletionNotice(
                "example.com", 1L, 0, OptionalLong.empty(), null));
    }
}
