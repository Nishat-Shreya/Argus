package com.argus.ui;

import java.util.Objects;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Shared motion library for every Argus screen.
 *
 * <p>Implemented in P0-02: {@link #fadeInUp(Node)} / {@link #fadeInUp(Node, Duration)},
 * plus the reduced-motion switch. Implemented in P1-05: {@link #shake(Node)}, the login
 * screen's validation-error feedback (plan section 7.11). Implemented in P1-06:
 * {@link #pulseDot(Node)}, the dashboard's live-activity indicator (plan §7.8).
 *
 * <p>Implemented in P3-06: {@link #expandField(Region, double)}, the click-to-expand annotation
 * field (plan §4.3).
 *
 * <p>Not yet implemented (build-prompt "Standard motion types") — add each one in the
 * backlog item that first needs it, never as an empty placeholder:
 * <pre>
 *   scaleIn          modals/popups                 — first needed by P2-09
 *   glowPulse        KEV / attention containers    — first needed by P2-07 UI
 *   rippleOnClick    primary buttons               — first needed by P1-06
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

    private static final Duration PULSE_DURATION = Duration.millis(600);
    private static final double PULSE_MIN_OPACITY = 0.3;

    private static final Duration EXPAND_DURATION = Duration.millis(180);

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

    /**
     * Live status indicator: an indefinitely repeating opacity pulse, {@code cycleCount =
     * INDEFINITE}, {@code autoReverse = true}. The caller (the dashboard controller) owns the
     * returned {@link FadeTransition} and MUST call {@code stop()} on it when the scan ends —
     * a forever-running timeline on a hidden node is a leak, exactly the same reasoning that
     * makes every scan thread in this item daemon AND explicitly joined.
     *
     * <p>Mirrors {@link #fadeInUp(Node)}'s guard contract: NPE on null, {@code
     * IllegalStateException} off the FX thread. Under reduced motion the dot is left fully
     * visible and static (opacity 1) and the returned transition is never started, but it is
     * still safe to {@code stop()} (idempotent no-op) — the same "skipped animation still
     * leaves the node in its final visible state" rule {@link #fadeInUp(Node)} follows.
     */
    public static FadeTransition pulseDot(Node node) {
        Objects.requireNonNull(node, "node");
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "pulseDot must be called on the JavaFX Application Thread");
        }

        FadeTransition pulse = new FadeTransition(PULSE_DURATION, node);
        pulse.setFromValue(1.0);
        pulse.setToValue(PULSE_MIN_OPACITY);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.setInterpolator(Interpolator.EASE_BOTH);

        if (isReducedMotion()) {
            node.setOpacity(1);
            return pulse;
        }

        pulse.play();
        return pulse;
    }

    /**
     * Click-to-expand field (spec.md "Click-to-expand field", P0-02 roadmap, first needed by
     * P3-06): grows a one-line input into a multi-line text area, or shrinks it back. The
     * caller owns both heights — they are screen layout values, not a shared rule.
     *
     * <p>Animates {@code prefHeight} only, via {@link Timeline} + {@link KeyValue} (the first
     * use of {@code Timeline} in this class — the other three helpers use {@code Transition}
     * subclasses, but {@code prefHeight} is an arbitrary {@code DoubleProperty} with no
     * purpose-built {@code Transition}), over {@link #EXPAND_DURATION} (180&nbsp;ms),
     * {@link Interpolator#EASE_OUT}. Under reduced motion the height is SET to
     * {@code targetHeight} immediately and nothing is played (P0-02's "a skipped animation still
     * leaves the node in its final state" rule).
     *
     * @throws NullPointerException     if field is null
     * @throws IllegalArgumentException if targetHeight is negative
     * @throws IllegalStateException    if called off the JavaFX Application Thread
     */
    public static void expandField(Region field, double targetHeight) {
        Objects.requireNonNull(field, "field");
        if (targetHeight < 0) {
            throw new IllegalArgumentException(
                    "targetHeight must not be negative: " + targetHeight);
        }
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "expandField must be called on the JavaFX Application Thread");
        }

        if (isReducedMotion()) {
            field.setPrefHeight(targetHeight);
            return;
        }

        Timeline timeline = new Timeline(new KeyFrame(EXPAND_DURATION,
                new KeyValue(field.prefHeightProperty(), targetHeight, Interpolator.EASE_OUT)));
        timeline.play();
    }
}
