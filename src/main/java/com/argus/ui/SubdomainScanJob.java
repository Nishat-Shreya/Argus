package com.argus.ui;

import com.argus.core.ScanProducerTask;
import com.argus.core.ScanSink;
import com.argus.core.Subdomain;
import com.argus.core.SubdomainEnumerator;

/**
 * Wraps {@link SubdomainEnumerator}. {@code cancel()} is a documented no-op: {@code
 * HttpClient.send} IS interruptible (P1-02's error contract), so {@code pool.shutdownNow()}'s
 * interrupt is the cancellation path.
 */
final class SubdomainScanJob implements ScanJob {

    private final String domain;
    private final SubdomainEnumerator enumerator = new SubdomainEnumerator();

    SubdomainScanJob(String domain) {
        this.domain = domain;
    }

    @Override
    public String name() {
        return "subdomain enumeration";
    }

    @Override
    public int run(ScanSink<Object> sink) throws Exception {
        return new ScanProducerTask<Subdomain>(() -> enumerator.enumerate(domain), sink).call();
    }

    @Override
    public void cancel() {
        // No-op by design: SubdomainEnumerator owns no thread pool and runs one blocking HTTP
        // call. Cancellation goes through Thread interruption (the pool's shutdownNow()), not
        // through this method.
    }
}
