package com.argus.ui;

import com.argus.core.ScanSink;

/** One cancellable unit of scan work that publishes into the pipeline. */
interface ScanJob {

    /** Display name, e.g. "port scan". Stable for the life of the job. */
    String name();

    /**
     * BLOCKING. Runs the scan on the calling thread and publishes each finding.
     *
     * @return the number of findings published
     * @throws InterruptedException if interrupted — interruption IS the cancellation mechanism
     */
    int run(ScanSink<Object> sink) throws Exception;

    /**
     * Best-effort cancellation. Safe from any thread, idempotent, returns promptly. May
     * legitimately be a no-op for jobs whose only cancellation path is interruption.
     */
    void cancel();
}
