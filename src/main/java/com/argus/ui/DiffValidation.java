package com.argus.ui;

import com.argus.core.ScanSummary;

/**
 * Invariant 8 for this screen (plan §3.5, §4.3) — the {@code LoginValidation} /
 * {@code DashboardValidation} / {@code ApiKeyValidation} twin. Both controls are constrained to
 * existing scans, so this covers what the controls cannot: both chosen, not the same scan, same
 * target, baseline earlier.
 */
final class DiffValidation {

    private DiffValidation() {
    }

    /** Checked in order: baseline null, current null, same id, different target, baseline after
     *  current. */
    static Result check(ScanSummary baseline, ScanSummary current) {
        if (baseline == null) {
            return Result.error("choose a baseline scan");
        }
        if (current == null) {
            return Result.error("choose a current scan");
        }
        if (baseline.id() == current.id()) {
            return Result.error("choose two different scans");
        }
        if (!baseline.target().equals(current.target())) {
            return Result.error("those two scans have different targets");
        }
        if (baseline.startedAt().isAfter(current.startedAt())) {
            return Result.error("the baseline must be the earlier scan");
        }
        return Result.ok();
    }

    record Result(boolean valid, String message) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result error(String message) {
            return new Result(false, message);
        }
    }
}
