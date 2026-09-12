package com.argus.ui;

import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Shared motion library for every Argus screen.
 *
 * <p>Implemented in P0-02: {@link #fadeInUp(Node)} / {@link #fadeInUp(Node, Duration)},
 * plus the reduced-motion switch. Implemented in P1-05: {@link #shake(Node)}, the login
 * screen's validation-error feedback (plan section 7.11).
 *
 * <p>Not yet implemented (build-prompt "Standard motion types") — add each one in the
 * backlog item that first needs it, never as an empty placeholder:
 * <pre>
 *   scaleIn          modals/popups                 — first needed by P2-09
 *   glowPulse        KEV / attention containers    — first needed by P2-07 UI
 *   pulseDot         live status indicator         — first needed by P1-06
 *   rippleOnClick    primary buttons               — first needed by P1-06
 *   click-to-expand  annotation field              — first needed by P3-06
 * </pre>
 */
public final class AnimationUtils {

    /** System property that forces reduced motion on: {@code -Dargus.reducedMotion=true}. */
    public static final String REDUCED_MOTION_PROPERTY = "argus.reducedMotion";

    private static final Duration DURATION = Duration.millis(220);
    private static final double OFFSET_Y = 8;

    private static final Duration SHAKE_LEG_DURATION = Duration.millis(55);
    private static final double SHAKE_OFFSET_X = 8;
    private static final int SHAKE_CYCLE_COUNT = 6;

    /**
     * Visibility-only shared flag. Every access is an unconditional whole-word read or
     * write — never a compound check-then-act — so {@code volatile} is sufficient and a
     * lock on this hot per-node read path would buy nothing (see plan §4).
     */
    private static volatile boolean reducedMotion =
            Boolean.getBoolean(REDUCED_MOTION_PROPERTY);

    private AnimationUtils() {
    }

    /** Default entrance: opacity 0&rarr;1 and translateY 8&rarr;0 over 220&nbsp;ms, no delay. */
    public static void fadeInUp(Node node) {
        fadeInUp(node, Duration.ZERO);
    }

    /** Staggered entrance: same motion, started after {@code delay}. */
    public static void fadeInUp(Node node, Duration delay) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(delay, "delay");
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "fadeInUp must be called on the JavaFX Application Thread");
        }

        if (isReducedMotion()) {
            node.setOpacity(1);
            node.setTranslateY(0);
            return;
        }

        node.setOpacity(0);
        node.setTranslateY(OFFSET_Y);

        FadeTransition fade = new FadeTransition(DURATION, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slide = new TranslateTransition(DURATION, node);
        slide.setFromY(OFFSET_Y);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition entrance = new ParallelTransition(fade, slide);
        entrance.setDelay(delay);
        entrance.play();
    }

    /** True when animations must be skipped. Seeded from the system property at class init. */
    public static boolean isReducedMotion() {
        return reducedMotion;
    }

    /** Runtime override for a future settings toggle and for tests. */
    public static void setReducedMotion(boolean reduced) {
        reducedMotion = reduced;
    }

    /**
     * Validation-error feedback: a horizontal shake, ~55&nbsp;ms per leg, {@code byX = 8},
     * {@code cycleCount = 6}, {@code autoReverse = true}. Mirrors {@link #fadeInUp(Node)}'s
     * guard contract exactly so the class stays predictable: {@code fadeInUp} owns
     * {@code translateY} and {@code shake} owns {@code translateX}, so they compose without
     * fighting.
     */
    public static void shake(Node node) {
        Objects.requireNonNull(node, "node");
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "shake must be called on the JavaFX Application Thread");
        }

        if (isReducedMotion()) {
            node.setTranslateX(0);
            return;
        }

        TranslateTransition shake = new TranslateTransition(SHAKE_LEG_DURATION, node);
        shake.setFromX(0);
        shake.setByX(SHAKE_OFFSET_X);
        shake.setCycleCount(SHAKE_CYCLE_COUNT);
        shake.setAutoReverse(true);
        shake.setInterpolator(Interpolator.EASE_OUT);
        shake.setOnFinished(event -> node.setTranslateX(0));
        shake.play();
    }
}
