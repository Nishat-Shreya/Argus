package com.argus.ui;

import com.argus.core.ScanProducerTask;
import com.argus.core.ScanSink;
import com.argus.core.Subdomain;
import com.argus.core.SubdomainEnumerationException;
import com.argus.core.SubdomainEnumerator;
import java.lang.System.Logger.Level;

/**
 * Wraps {@link SubdomainEnumerator}. {@code cancel()} is a documented no-op: {@code
 * HttpClient.send} IS interruptible (P1-02's error contract), so {@code pool.shutdownNow()}'s
 * interrupt is the cancellation path.
 */
final class SubdomainScanJob implements ScanJob {

    private static final System.Logger LOGGER = System.getLogger(SubdomainScanJob.class.getName());

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
        try {
            return new ScanProducerTask<Subdomain>(() -> enumerator.enumerate(domain), sink).call();
        } catch (SubdomainEnumerationException e) {
            // The coordinator's log line deliberately names only the exception class (it never
            // echoes getMessage()), so the actual crt.name reason -- HTTP status and crt.name's own
            // explanation, e.g. "crt.name returned HTTP 502" -- is reported here, then rethrown
            // unchanged so the job still fails exactly as before.
            LOGGER.log(Level.WARNING, "subdomain enumeration failed for " + domain + ": "
                    + e.getMessage());
            throw e;
        }
    }

    @Override
    public void cancel() {
        // No-op by design: SubdomainEnumerator owns no thread pool and runs one blocking HTTP
        // call. Cancellation goes through Thread interruption (the pool's shutdownNow()), not
        // through this method.
    }
}
