package com.argus.ui;

import java.util.List;

/**
 * Builds the jobs for a plan. The injection seam that keeps {@code ScanCoordinator}'s tests
 * socket-free and network-free — the {@code SocketConnector}/{@code HttpFetcher} pattern
 * (P1-01 §3.5).
 */
interface ScanJobFactory {
    List<ScanJob> jobsFor(ScanPlan plan);
}
