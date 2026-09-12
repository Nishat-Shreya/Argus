package com.argus.ui;

/** Progress over producer completion, not over ports (§7.1). */
record ScanProgress(int completedJobs, int totalJobs, int findingsDelivered) {

    /** 0.0 when totalJobs is 0; otherwise completedJobs / totalJobs. Feeds ProgressBar directly. */
    double fraction() {
        return totalJobs == 0 ? 0.0 : (double) completedJobs / totalJobs;
    }
}
