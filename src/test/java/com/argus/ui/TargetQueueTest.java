package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanCompletion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P3-05 §6: Q1-Q12 pin {@link TargetQueue} -- FX-thread-confined, no lock, no volatile, no
 * concurrent collection (plan §3.5, §4.1). NOT the invariant-4 producer-consumer pipeline.
 */
class TargetQueueTest {

    @Test
    void q1AdmitOfThreeAcceptedTargetsQueuesAllThreeInOrder() {
        TargetQueue queue = new TargetQueue();
        TargetDrop drop = new TargetDrop(List.of("a.com", "b.com", "c.com"), List.of());

        QueueAdmission admission = queue.admit(drop, null);

        assertEquals(List.of("a.com", "b.com", "c.com"), admission.queued());
        assertEquals(List.of("a.com", "b.com", "c.com"), queue.pending());
    }

    @Test
    void q2ReAdmittingAPendingTargetIsADuplicateAndSizeIsUnchanged() {
        TargetQueue queue = new TargetQueue();
        queue.admit(new TargetDrop(List.of("a.com"), List.of()), null);

        QueueAdmission admission = queue.admit(new TargetDrop(List.of("a.com"), List.of()), null);

        assertEquals(List.of("a.com"), admission.duplicates());
        assertTrue(admission.queued().isEmpty());
        assertEquals(1, queue.size());
    }

    @Test
    void q3AdmittingTheActiveTargetIsADuplicate() {
        TargetQueue queue = new TargetQueue();

        QueueAdmission admission =
                queue.admit(new TargetDrop(List.of("running.com"), List.of()), "running.com");

        assertEquals(List.of("running.com"), admission.duplicates());
        assertTrue(queue.isEmpty());
    }

    @Test
    void q4ActiveTargetNullAdmitsNormally() {
        TargetQueue queue = new TargetQueue();

        QueueAdmission admission =
                queue.admit(new TargetDrop(List.of("a.com"), List.of()), null);

        assertEquals(List.of("a.com"), admission.queued());
    }

    @Test
    void q5ADropsRejectedListPassesStraightThrough() {
        TargetQueue queue = new TargetQueue();
        TargetDrop drop = new TargetDrop(List.of("a.com"), List.of("junk", "nope"));

        QueueAdmission admission = queue.admit(drop, null);

        assertEquals(List.of("junk", "nope"), admission.rejected());
    }

    @Test
    void q6OverflowQueuesUpToTheCapAndCountsTheRest() {
        TargetQueue queue = new TargetQueue();
        List<String> targets = new java.util.ArrayList<>();
        for (int i = 0; i < TargetQueue.MAX_PENDING + 5; i++) {
            targets.add("t" + i + ".com");
        }

        QueueAdmission admission = queue.admit(new TargetDrop(targets, List.of()), null);

        assertEquals(TargetQueue.MAX_PENDING, admission.queued().size());
        assertEquals(5, admission.overflow());
        assertEquals(TargetQueue.MAX_PENDING, queue.size());
    }

    @Test
    void q7PollReturnsFifoAndShrinksTheQueue() {
        TargetQueue queue = new TargetQueue();
        queue.admit(new TargetDrop(List.of("a.com", "b.com"), List.of()), null);

        assertEquals(Optional.of("a.com"), queue.poll());
        assertEquals(1, queue.size());
        assertEquals(Optional.of("b.com"), queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void q8PollOnEmptyReturnsEmptyOptional() {
        TargetQueue queue = new TargetQueue();

        assertEquals(Optional.empty(), queue.poll());
    }

    @Test
    void q9RemoveOfAPendingTargetReturnsTrueAndRemovesItOfAnAbsentOneReturnsFalse() {
        TargetQueue queue = new TargetQueue();
        queue.admit(new TargetDrop(List.of("a.com"), List.of()), null);

        assertTrue(queue.remove("a.com"));
        assertTrue(queue.isEmpty());
        assertFalse(queue.remove("a.com"));
    }

    @Test
    void q10PendingIsUnmodifiableAndIsASnapshot() {
        TargetQueue queue = new TargetQueue();
        queue.admit(new TargetDrop(List.of("a.com"), List.of()), null);

        List<String> snapshot = queue.pending();
        queue.admit(new TargetDrop(List.of("b.com"), List.of()), null);

        assertEquals(List.of("a.com"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("c.com"));
    }

    @Test
    void q11AdvancesAfterFollowsTheSectionZeroPointThreeTable() {
        assertTrue(TargetQueue.advancesAfter(ScanCompletion.COMPLETED));
        assertTrue(TargetQueue.advancesAfter(ScanCompletion.COMPLETED_WITH_ERRORS));
        assertFalse(TargetQueue.advancesAfter(ScanCompletion.CANCELLED));
    }

    @Test
    void q12AdmitOfNullDropThrowsNullPointerException() {
        TargetQueue queue = new TargetQueue();

        assertThrows(NullPointerException.class, () -> queue.admit(null, null));
    }
}
