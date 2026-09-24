package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertThrows;

import javafx.util.Duration;
import org.junit.jupiter.api.Test;

/**
 * Login screen polish batch: {@code AnimationUtils.glowPulse}, {@code bindFocusGlow} and the
 * {@code pulseDot(Node, Duration, double)} overload — no started toolkit, the {@code
 * AnimationUtilsExpandFieldTest} precedent (P0-02's rule: UI tests may *load* JavaFX classes but
 * must never *start* the toolkit), so only the null guards ahead of the FX-thread check are
 * reachable here.
 */
class AnimationUtilsGlowTest {

    @Test
    void glowPulseRejectsANullNode() {
        assertThrows(NullPointerException.class, () -> AnimationUtils.glowPulse(null));
    }

    @Test
    void bindFocusGlowRejectsANullField() {
        assertThrows(NullPointerException.class, () -> AnimationUtils.bindFocusGlow(null));
    }

    @Test
    void pulseDotWithPeriodRejectsANullNode() {
        assertThrows(NullPointerException.class,
                () -> AnimationUtils.pulseDot(null, Duration.millis(500), 0.5));
    }

    @Test
    void pulseDotWithPeriodRejectsANullPeriod() {
        assertThrows(NullPointerException.class,
                () -> AnimationUtils.pulseDot(new javafx.scene.shape.Circle(), null, 0.5));
    }
}
