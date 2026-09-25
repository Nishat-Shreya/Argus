package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanAlert;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanCompletionNotice;
import com.argus.core.ScanComparison;
import com.argus.core.ScanSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

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

    /**
     * Gate 3, off the FX thread: the single decision — is this comparison worth alerting on,
     * and what does the alert carry. Empty iff {@code comparison.diff().added()} is empty (R3
     * — {@code removed} and {@code changed} are ignored). The ONE authority feeding every
     * {@code AlertChannel} (the desktop tray via {@link #desktopNotification}, the webhook
     * channel directly).
     */
    static Optional<ScanAlert> alertFor(ScanComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        List<FindingSnapshot> added = comparison.diff().added();
        if (added.isEmpty()) {
            return Optional.empty();
        }

        int count = added.size();
        int listed = Math.min(count, ScanAlert.MAX_SUBJECTS);
        List<String> subjects = new ArrayList<>(listed);
        for (int i = 0; i < listed; i++) {
            subjects.add(describe(added.get(i)));
        }

        return Optional.of(new ScanAlert(comparison.current().target(), comparison.baseline().id(),
                comparison.current().id(), count, subjects));
    }

    /** The desktop projection of an alert — byte-identical text to P3-02's original
     *  {@code forComparison}. */
    /**
     * The every-successful-scan email's content. Only a scan that {@link #eligible} (saved and
     * {@code COMPLETED}) may produce one -- a failed, errored or cancelled scan never emails --
     * so anything else is a caller bug and throws. Pure.
     *
     * @param baselineScanId the earlier completed scan of this target, when one exists
     * @param newFindings    the new-findings alert, when the comparison found something new
     */
    static ScanCompletionNotice completionNotice(ScanOutcome outcome,
            Optional<Long> baselineScanId, Optional<ScanAlert> newFindings) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(baselineScanId, "baselineScanId");
        Objects.requireNonNull(newFindings, "newFindings");
        if (!eligible(outcome)) {
            throw new IllegalArgumentException(
                    "only a saved scan that completed without errors may produce a notice");
        }
        OptionalLong baseline = baselineScanId.isPresent()
                ? OptionalLong.of(baselineScanId.get()) : OptionalLong.empty();
        return new ScanCompletionNotice(outcome.run().target(), outcome.savedScanId(),
                outcome.findingsDelivered(), baseline, newFindings);
    }

    /** Why a completed scan produced no alert (and therefore no email/webhook/desktop message) --
     *  shown in the dashboard log so a silent "nothing sent" is never a mystery. Pure. */
    static String noAlertReason(String target, Optional<Long> baselineScanId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(baselineScanId, "baselineScanId");
        if (baselineScanId.isEmpty()) {
            return "no earlier completed scan of " + target
                    + " to compare against (first scan of this target)";
        }
        return "no new findings compared with scan #" + baselineScanId.get();
    }

    static DesktopNotification desktopNotification(ScanAlert alert) {
        Objects.requireNonNull(alert, "alert");
        int count = alert.addedCount();
        String caption = "Argus · " + count + (count == 1 ? " new finding" : " new findings");

        StringBuilder text = new StringBuilder(alert.target()).append(" · ");
        int listed = Math.min(count, MAX_LISTED_SUBJECTS);
        List<String> subjects = alert.addedSubjects();
        for (int i = 0; i < listed; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(subjects.get(i));
        }
        if (count > MAX_LISTED_SUBJECTS) {
            text.append(" and ").append(count - MAX_LISTED_SUBJECTS).append(" more");
        }

        return new DesktopNotification(caption, text.toString());
    }

    /** Gate 3, off the FX thread: the message, or empty when nothing was added (R3 — {@code
     *  removed} and {@code changed} are ignored). UNCHANGED signature; the body is now a
     *  one-line projection of {@link #alertFor} so there remains exactly one decision
     *  authority (plan §3.3, R6). */
    static Optional<DesktopNotification> forComparison(ScanComparison comparison) {
        return alertFor(comparison).map(ScanNotifications::desktopNotification);
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
