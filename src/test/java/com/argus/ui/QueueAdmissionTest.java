package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P3-05 §6: A1-A6 pin {@link QueueAdmission#describe()} exactly -- the single wording authority
 * for the queue status label and the log console (plan §3.3, the {@code Reports.hiddenNote}
 * precedent).
 */
class QueueAdmissionTest {

    @Test
    void a1AllAcceptedDescribesQueuedCount() {
        QueueAdmission admission =
                new QueueAdmission(List.of("a.com", "b.com", "c.com"), List.of(), List.of(), 0);

        assertEquals("queued 3", admission.describe());
    }

    @Test
    void a2WithDuplicatesAppendsTheDuplicateCount() {
        QueueAdmission admission =
                new QueueAdmission(List.of("a.com", "b.com"), List.of("c.com"), List.of(), 0);

        assertEquals("queued 2 · 1 already queued", admission.describe());
    }

    @Test
    void a3WithRejectsListsTheirNames() {
        QueueAdmission admission =
                new QueueAdmission(List.of("a.com"), List.of(), List.of("x", "y"), 0);

        assertEquals("queued 1 · 2 not a valid domain (x, y)", admission.describe());
    }

    @Test
    void a4MoreThanThreeRejectsShowsExactlyThreeNamesPlusEllipsis() {
        QueueAdmission admission = new QueueAdmission(
                List.of("a.com"), List.of(), List.of("w", "x", "y", "z"), 0);

        assertEquals("queued 1 · 4 not a valid domain (w, x, y …)", admission.describe());
    }

    @Test
    void a5OverflowIsReportedWithACount() {
        QueueAdmission admission =
                new QueueAdmission(List.of("a.com", "b.com"), List.of(), List.of(), 4);

        assertEquals("queued 2 · 4 over the queue limit", admission.describe());
    }

    @Test
    void a6NothingAtAllHasItsOwnSentence() {
        assertEquals("nothing to queue — no valid domain in the drop",
                QueueAdmission.nothing().describe());
    }
}
