package com.argus.ui;

/**
 * The operator-added delete-confirmation decision (this project's first modal dialog — CHANGED
 * from the plan's own R9 default of one-click, no-confirmation delete, at the operator's
 * request: deleting operator-authored text with no undo warrants a confirmation).
 *
 * Pure and toolkit-free: it knows nothing about HOW a confirmation is obtained — only that a
 * delete proceeds if and only if a note is selected AND the operator confirmed. The controller
 * wires this to a real {@code javafx.scene.control.Alert}'s result
 * ({@code Alert.AlertType.CONFIRMATION}, styled via {@code Theme.applyTo(...)} on the dialog
 * pane's scene); nothing here ever touches JavaFX, so the gate itself is unit-testable without
 * driving a real dialog in a headless test.
 */
final class NoteDeletion {

    private NoteDeletion() {
    }

    /** True iff a delete should proceed. False on cancel, false on close, false with nothing
     *  selected regardless of the confirmation outcome. */
    static boolean shouldDelete(boolean noteSelected, boolean confirmed) {
        return noteSelected && confirmed;
    }
}
