package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 6.1 of the P3-03 plan: {@link ScanAlert}. Pure, toolkit-free, no clock.
 */
class ScanAlertTest {

    @Test
    void a1HappyPathRoundTrips() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 3,
                List.of("a.example.com", "b.example.com", "c.example.com"));
        assertEquals("example.com", alert.target());
        assertEquals(1L, alert.baselineScanId());
        assertEquals(2L, alert.currentScanId());
        assertEquals(3, alert.addedCount());
        assertEquals(List.of("a.example.com", "b.example.com", "c.example.com"),
                alert.addedSubjects());
        assertFalse(alert.subjectsTruncated());
    }

    @Test
    void a2TruncationFlagWhenSubjectsFewerThanCount() {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < ScanAlert.MAX_SUBJECTS; i++) {
            subjects.add("host" + i + ".example.com");
        }
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 62, subjects);
        assertEquals(62, alert.addedCount());
        assertEquals(ScanAlert.MAX_SUBJECTS, alert.addedSubjects().size());
        assertTrue(alert.subjectsTruncated());
    }

    @Test
    void a3ZeroAddedCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 0, List.of("a.example.com")));
    }

    @Test
    void a4EmptySubjectsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 1, List.of()));
    }

    @Test
    void a5TooManySubjectsIsRejected() {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < ScanAlert.MAX_SUBJECTS + 1; i++) {
            subjects.add("host" + i + ".example.com");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, subjects.size(), subjects));
    }

    @Test
    void a6SubjectsLargerThanAddedCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 1,
                        List.of("a.example.com", "b.example.com")));
    }

    @Test
    void a7Malformed() {
        assertThrows(NullPointerException.class,
                () -> new ScanAlert(null, 1L, 2L, 1, List.of("a.example.com")));
        assertThrows(NullPointerException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 1, null));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 1, withNull));
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, 2L, 1, List.of("   ")));
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", -1L, 2L, 1, List.of("a.example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new ScanAlert("example.com", 1L, -2L, 1, List.of("a.example.com")));
    }

    @Test
    void a8BlankTargetIsAccepted() {
        ScanAlert alert = new ScanAlert("   ", 1L, 2L, 1, List.of("a.example.com"));
        assertEquals("   ", alert.target());
    }

    @Test
    void a9Immutability() {
        List<String> mutable = new ArrayList<>(Arrays.asList("a.example.com", "b.example.com"));
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 2, mutable);
        mutable.add("c.example.com");
        assertEquals(2, alert.addedSubjects().size());
        assertThrows(UnsupportedOperationException.class,
                () -> alert.addedSubjects().add("d.example.com"));
        assertThrows(UnsupportedOperationException.class,
                () -> Collections.shuffle(alert.addedSubjects()));
    }
}
