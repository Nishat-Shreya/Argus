package com.argus.ui;

/**
 * Invariant 8 for the scheduled-scans screen's two fields (P3-08) -- the {@code
 * DashboardValidation} / {@code TagValidation} twin. Target validation is not restated: it
 * delegates to {@link DashboardValidation#check} so a scheduled target and a typed target are
 * held to exactly the same rule.
 */
final class ScheduledScanValidation {

    static final int MAX_INTERVAL_MINUTES = 10_080; // one week

    private ScheduledScanValidation() {
    }

    static TargetResult checkTarget(String targetText) {
        DashboardValidation.Result result = DashboardValidation.check(targetText);
        return result.valid()
                ? TargetResult.ok(result.target())
                : TargetResult.error(result.message());
    }

    /** Checked in order: {@code null}/blank &rarr; error; not a whole number &rarr; error; not
     *  positive &rarr; error; over {@link #MAX_INTERVAL_MINUTES} &rarr; error; else ok. */
    static IntervalResult checkInterval(String intervalText) {
        if (intervalText == null || intervalText.isBlank()) {
            return IntervalResult.error("interval must not be blank");
        }
        int minutes;
        try {
            minutes = Integer.parseInt(intervalText.strip());
        } catch (NumberFormatException e) {
            return IntervalResult.error("interval must be a whole number of minutes");
        }
        if (minutes <= 0) {
            return IntervalResult.error("interval must be a positive number of minutes");
        }
        if (minutes > MAX_INTERVAL_MINUTES) {
            return IntervalResult.error(
                    "interval must be at most " + MAX_INTERVAL_MINUTES + " minutes");
        }
        return IntervalResult.ok(minutes);
    }

    record TargetResult(boolean valid, String message, String target) {
        static TargetResult ok(String target) {
            return new TargetResult(true, null, target);
        }

        static TargetResult error(String message) {
            return new TargetResult(false, message, null);
        }
    }

    record IntervalResult(boolean valid, String message, int minutes) {
        static IntervalResult ok(int minutes) {
            return new IntervalResult(true, null, minutes);
        }

        static IntervalResult error(String message) {
            return new IntervalResult(false, message, 0);
        }
    }
}
