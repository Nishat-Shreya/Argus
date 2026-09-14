package com.argus.ui;

import com.argus.core.ScanCompletion;
import com.argus.core.ScanRun;

record ScanOutcome(ScanRun run, int failedJobs, Long savedScanId) {

    ScanCompletion result() {
        return run.completion();
    }

    int findingsDelivered() {
        return run.findings().size();
    }

    boolean saved() {
        return savedScanId != null;
    }
}
