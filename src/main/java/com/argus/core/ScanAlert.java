package com.argus.core;

import java.util.List;
import java.util.Objects;

/**
 * One "new findings were discovered" alert, independent of how it is delivered. Immutable, no
 * clock, no toolkit, no {@code db} type. Produced by {@code ui.ScanNotifications}; consumed by
 * every {@code AlertChannel} (desktop tray today, webhook here, email at P3-17).
 */
public record ScanAlert(String target, long baselineScanId, long currentScanId, int addedCount,
        List<String> addedSubjects) {

    /** Hard cap on the listed subjects. {@code addedCount} always carries the TRUE total. */
    public static final int MAX_SUBJECTS = 50;

    public ScanAlert {
        Objects.requireNonNull(target, "target must not be null");
        // Deliberately no blank check on target: ScanSummary itself only requires non-null, so
        // a blank check here would make forComparison throw where it previously returned a
        // notification. Behaviour preservation beats tidiness (plan §3.1).
        if (baselineScanId < 0) {
            throw new IllegalArgumentException("baselineScanId must be >= 0");
        }
        if (currentScanId < 0) {
            throw new IllegalArgumentException("currentScanId must be >= 0");
        }
        if (addedCount < 1) {
            throw new IllegalArgumentException("addedCount must be >= 1");
        }
        Objects.requireNonNull(addedSubjects, "addedSubjects must not be null");
        if (addedSubjects.isEmpty()) {
            throw new IllegalArgumentException("addedSubjects must not be empty");
        }
        if (addedSubjects.size() > MAX_SUBJECTS) {
            throw new IllegalArgumentException(
                    "addedSubjects must not exceed " + MAX_SUBJECTS + " entries");
        }
        if (addedSubjects.size() > addedCount) {
            throw new IllegalArgumentException(
                    "addedSubjects must not exceed addedCount");
        }
        for (String subject : addedSubjects) {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException(
                        "addedSubjects must not contain a null or blank element");
            }
        }
        addedSubjects = List.copyOf(addedSubjects);
    }

    /** True when {@link #addedSubjects()} lists fewer than {@link #addedCount()} — the
     *  receiver must not assume the list is exhaustive. */
    public boolean subjectsTruncated() {
        return addedSubjects.size() < addedCount;
    }
}
