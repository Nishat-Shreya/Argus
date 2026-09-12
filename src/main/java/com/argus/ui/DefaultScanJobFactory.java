package com.argus.ui;

import com.argus.core.ScanRequest;
import java.util.List;

/** Production factory: [ SubdomainScanJob(plan.target()), PortScanJob(ScanRequest.of(...)) ]. */
final class DefaultScanJobFactory implements ScanJobFactory {

    @Override
    public List<ScanJob> jobsFor(ScanPlan plan) {
        return List.of(
                new SubdomainScanJob(plan.target()),
                new PortScanJob(ScanRequest.of(plan.target(), plan.ports())));
    }
}
