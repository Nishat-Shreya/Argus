package com.argus.ui;

import java.util.List;
import java.util.Objects;

/**
 * One target's whole timeline axis (plan §3.2): its points, oldest-first, plus
 * {@code totalForTarget} — the pre-cap count of completed scans, so a truncated axis can still
 * report the full total.
 */
record TimelineTrack(String target, List<TimelinePoint> points, int totalForTarget) {

    TimelineTrack {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(points, "points");
        points = List.copyOf(points);
        if (totalForTarget < points.size()) {
            throw new IllegalArgumentException(
                    "totalForTarget (" + totalForTarget + ") must not be smaller than the "
                            + "point count (" + points.size() + ")");
        }
    }

    int size() {
        return points.size();
    }

    boolean isEmpty() {
        return points.isEmpty();
    }

    boolean truncated() {
        return totalForTarget > points.size();
    }

    TimelinePoint point(int index) {
        return points.get(index);
    }

    long scanIdAt(int index) {
        return points.get(index).scanId();
    }

    /** {@code max(0, size() - 1)} — what the controller feeds {@code Slider.setMax}. */
    double sliderMax() {
        return Math.max(0, size() - 1);
    }

    /** Load order for the prefetch {@code Task}, oldest-first. */
    List<Long> scanIds() {
        return points.stream().map(TimelinePoint::scanId).toList();
    }
}
