package com.argus.ui;

import java.util.List;

/**
 * How {@code ScanCoordinator} reports to whoever is watching. Toolkit-free on purpose: the
 * coordinator never imports {@code Platform}, and the {@code Platform.runLater()} call lives in
 * exactly one place, the controller (invariant 3).
 *
 * THREADING: every method is called from a background thread (the supervisor or the consumer),
 * NEVER from the FX Application Thread. Implementations must not block and must marshal to the
 * FX thread themselves.
 *
 * ORDERING: {@code onScanStarted} fires first; {@code onScanFinished} fires exactly once and
 * last (the supervisor fires it only after joining the consumer). Ordering BETWEEN
 * {@code onFindings} (consumer thread) and {@code onWorkerStatus}/{@code onProgress}
 * (supervisor thread) is not defined and nothing needs it.
 */
interface ScanEventListener {
    void onScanStarted(ScanPlan plan, List<String> jobNames);

    void onLog(String line);

    void onWorkerStatus(WorkerStatus status);

    void onFindings(List<FindingRow> batch);

    void onProgress(ScanProgress progress);

    void onScanFinished(ScanOutcome outcome);
}
