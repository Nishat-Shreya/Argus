package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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

    // ---- scan-completed notice (sent after EVERY successful scan) -----------------------

    @Test
    void completedSubjectForAScanWithNothingNewStatesZeroNew() {
        ScanCompletionNotice notice = new ScanCompletionNotice("kuet.ac.bd", 57L, 73,
                OptionalLong.of(54L), Optional.empty());

        assertEquals("Argus: scan #57 of kuet.ac.bd completed (73 findings, 0 new)",
                EmailPayloads.subject(notice));
    }

    @Test
    void completedSubjectForAFirstScanOmitsTheNewCount() {
        ScanCompletionNotice notice = new ScanCompletionNotice("ruet.ac.bd", 58L, 12,
                OptionalLong.empty(), Optional.empty());

        assertEquals("Argus: scan #58 of ruet.ac.bd completed (12 findings)",
                EmailPayloads.subject(notice));
    }

    @Test
    void completedSubjectWithNewFindingsCountsThem() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 2,
                List.of("a.example.com", "b.example.com"));
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 2L, 10,
                OptionalLong.of(1L), Optional.of(alert));

        assertEquals("Argus: scan #2 of example.com completed (10 findings, 2 new)",
                EmailPayloads.subject(notice));
    }

    @Test
    void completedBodyForNothingNewSaysSoAndNamesTheBaseline() {
        ScanCompletionNotice notice = new ScanCompletionNotice("kuet.ac.bd", 57L, 73,
                OptionalLong.of(54L), Optional.empty());
        String body = EmailPayloads.body(notice);

        assertTrue(body.contains("scan #57 of kuet.ac.bd completed"));
        assertTrue(body.contains("Findings in this scan: 73"));
        assertTrue(body.contains("scan #54"));
        assertTrue(body.contains("0 new finding(s)"));
    }

    @Test
    void completedBodyForAFirstScanExplainsThereIsNothingToCompareWith() {
        ScanCompletionNotice notice = new ScanCompletionNotice("ruet.ac.bd", 58L, 12,
                OptionalLong.empty(), Optional.empty());
        String body = EmailPayloads.body(notice);

        assertTrue(body.contains("Findings in this scan: 12"));
        assertTrue(body.contains("no earlier completed scan"));
    }

    @Test
    void completedBodyListsEveryNewSubjectAndNotesTruncation() {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < ScanAlert.MAX_SUBJECTS; i++) {
            subjects.add("host" + i + ".example.com");
        }
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, ScanAlert.MAX_SUBJECTS + 5,
                subjects);
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 2L, 99,
                OptionalLong.of(1L), Optional.of(alert));
        String body = EmailPayloads.body(notice);

        assertTrue(body.contains("55 new finding(s)"));
        assertTrue(body.contains("host0.example.com"));
        assertTrue(body.contains("truncated"));
    }

    @Test
    void completedMessagesAreAsciiOnlyBecauseTheSmtpTransportWritesUsAscii() {
        ScanCompletionNotice notice = new ScanCompletionNotice("kuet.ac.bd", 57L, 73,
                OptionalLong.of(54L), Optional.empty());

        assertTrue(EmailPayloads.subject(notice).chars().allMatch(c -> c < 128));
        assertTrue(EmailPayloads.body(notice).chars().allMatch(c -> c < 128));
    }
}
