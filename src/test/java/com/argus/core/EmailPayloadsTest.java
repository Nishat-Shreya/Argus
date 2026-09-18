package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailPayloadsTest {

    @Test
    void subjectMentionsCountAndTarget() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 3, List.of("a", "b", "c"));
        assertEquals("Argus: 3 new finding(s) on example.com", EmailPayloads.subject(alert));
    }

    @Test
    void bodyListsEveryAddedSubjectAndTheScanIds() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 2,
                List.of("a.example.com", "b.example.com"));
        String body = EmailPayloads.body(alert);

        assertTrue(body.contains("2 new finding(s)"));
        assertTrue(body.contains("example.com"));
        assertTrue(body.contains("#1"));
        assertTrue(body.contains("#2"));
        assertTrue(body.contains("a.example.com"));
        assertTrue(body.contains("b.example.com"));
    }

    @Test
    void bodyNotesTruncationWhenTheListIsIncomplete() {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < ScanAlert.MAX_SUBJECTS; i++) {
            subjects.add("host" + i + ".example.com");
        }
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, ScanAlert.MAX_SUBJECTS + 5, subjects);

        String body = EmailPayloads.body(alert);
        assertTrue(body.contains("truncated"));
    }

    @Test
    void bodyContainsNoTruncationNoteWhenTheListIsComplete() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));
        assertTrue(!EmailPayloads.body(alert).contains("truncated"));
    }
}
