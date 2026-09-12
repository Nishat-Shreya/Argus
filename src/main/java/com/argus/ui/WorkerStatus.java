package com.argus.ui;

/** One producer job's status. {@code threadName} is the coordinator's OWN pool thread (§7.6). */
record WorkerStatus(String jobName, String threadName, State state, int findingsPublished) {

    enum State { PENDING, RUNNING, DONE, FAILED, CANCELLED }
}
