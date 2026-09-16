package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Plan §6.7: {@code AnimationUtils.expandField} — no started toolkit. P0-02's rule: UI tests may
 * *load* JavaFX classes but must never *start* the toolkit.
 */
class AnimationUtilsExpandFieldTest {

    @Test
    void rejectsANullField() {
        assertThrows(NullPointerException.class, () -> AnimationUtils.expandField(null, 10));
    }
}
