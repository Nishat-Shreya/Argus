package com.argus.ui;

record ScanOutcome(Result result, int findingsDelivered, int failedJobs) {

    enum Result { COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }
}
