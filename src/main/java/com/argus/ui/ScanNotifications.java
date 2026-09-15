package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanComparison;
import com.argus.core.ScanSummary;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every rule for whether to notify a completed scan and what it says (plan §3.5). Pure, no
 * I/O, no clock, no toolkit. The {@code ScanChoices} / {@code Timelines} / {@code GraphModels}
 * pattern: one final, uninstantiable class holding every rule, so the controller holds none.
 *
 * "new" is not a judgement — it is {@code ScanDiffReport}'s {@code added} set, verbatim. No
 * threshold, no band, no scoring, no severity vocabulary of any kind (§0.1 of the plan).
 */
final class ScanNotifications {

    static final int MAX_LISTED_SUBJECTS = 3;

    private ScanNotifications() {
    }

    /** Gate 1, on the FX thread: is this outcome even a candidate? A cancelled or errored scan
     *  has a partial finding set, so its "additions" would be unreliable (R4). */
    static boolean eligible(ScanOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return outcome.saved() && outcome.result() == ScanCompletion.COMPLETED;
    }

    /**
     * Gate 2, off the FX thread: the most recent COMPLETED scan of {@code target} before
     * {@code currentScanId}, selected by the greatest {@code id} — not by timestamp, so this
     * reads no clock and needs no tie-breaking rule (R5).
     */
    static Optional<Long> baselineScanId(List<ScanSummary> summaries, String target,
            long currentScanId) {
        Objects.requireNonNull(summaries, "summaries");
        Objects.requireNonNull(target, "target");
        Long best = null;
        for (ScanSummary summary : summaries) {
            Objects.requireNonNull(summary, "summaries must not contain a null element");
            if (!target.equals(summary.target())) {
                continue;
            }
            if (!summary.complete()) {
                continue;
            }
            if (summary.id() >= currentScanId) {
                continue;
            }
            if (best == null || summary.id() > best) {
                best = summary.id();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Gate 3, off the FX thread: the message, or empty when nothing was added (R3 — {@code
     *  removed} and {@code changed} are ignored). */
    static Optional<DesktopNotification> forComparison(ScanComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        List<FindingSnapshot> added = comparison.diff().added();
        if (added.isEmpty()) {
            return Optional.empty();
        }

        int count = added.size();
        String caption = "Argus · " + count + (count == 1 ? " new finding" : " new findings");

        StringBuilder text = new StringBuilder(comparison.current().target()).append(" · ");
        int listed = Math.min(count, MAX_LISTED_SUBJECTS);
        for (int i = 0; i < listed; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(describe(added.get(i)));
        }
        if (count > MAX_LISTED_SUBJECTS) {
            text.append(" and ").append(count - MAX_LISTED_SUBJECTS).append(" more");
        }

        return Optional.of(new DesktopNotification(caption, text.toString()));
    }

    /** {@code "api.example.com"} / {@code "10.0.0.1:8080"} — the opaque-vocabulary probe rule.
     *  A half-null row degrades to its subject rather than throwing or being dropped. */
    static String describe(FindingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.port() != null && snapshot.state() != null) {
            return snapshot.subject() + ":" + snapshot.port();
        }
        return snapshot.subject();
    }
}
