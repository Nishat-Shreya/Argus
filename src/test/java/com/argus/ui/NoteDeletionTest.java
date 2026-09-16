package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The operator-added delete-confirmation decision (this project's first modal dialog, a
 * confirmation {@code Alert} before deleting a note — CHANGED from the plan's R9 default of
 * one-click delete). Pure and toolkit-free: it knows nothing about HOW the confirmation was
 * obtained — only that a delete proceeds if and only if a note was selected AND the operator
 * confirmed. The controller wires this to a real {@code Alert}'s result; this test wires it to
 * plain booleans, so the deletion-trigger logic is unit-testable without driving a real dialog.
 */
class NoteDeletionTest {

    @Test
    void proceedsWhenSelectedAndConfirmed() {
        assertTrue(NoteDeletion.shouldDelete(true, true));
    }

    @Test
    void doesNotProceedWhenSelectedButCancelled() {
        assertFalse(NoteDeletion.shouldDelete(true, false));
    }

    @Test
    void doesNotProceedWhenNothingIsSelectedEvenIfConfirmed() {
        assertFalse(NoteDeletion.shouldDelete(false, true));
    }

    @Test
    void doesNotProceedWhenNothingIsSelectedAndNotConfirmed() {
        assertFalse(NoteDeletion.shouldDelete(false, false));
    }
}
