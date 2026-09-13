package com.argus.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The total result of fanning one {@link IntelSubject} out across every {@link IntelSource}
 * injected into a {@link ThreatIntelClient}: one {@link IntelSourceOutcome} per source, in
 * injected order, always — {@code outcomes().size()} equals the number of injected sources,
 * regardless of how many were queried.
 *
 * No merged verdict, no combined score, no {@code anyFlagged()} — the four sources' scores are
 * not on a common scale (plan §3.2). The one aggregation performed is {@link #allCveIds()}, a
 * set union deduplicated on canonical {@link CveId}, never on a raw string.
 */
public record IntelReport(IntelSubject subject, List<IntelSourceOutcome> outcomes) {

    public IntelReport {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(outcomes, "outcomes must not be null");
        List<IntelSourceOutcome> copy = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (IntelSourceOutcome outcome : outcomes) {
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (!seenNames.add(outcome.sourceName())) {
                throw new IllegalArgumentException(
                        "duplicate sourceName in outcomes: " + outcome.sourceName());
            }
            copy.add(outcome);
        }
        outcomes = List.copyOf(copy);
    }

    /** Successful results only, in injected-source order. Never null. */
    public List<IntelResult> results() {
        List<IntelResult> results = new ArrayList<>();
        for (IntelSourceOutcome outcome : outcomes) {
            if (outcome.isOk()) {
                results.add(outcome.result());
            }
        }
        return List.copyOf(results);
    }

    /** FAILED outcomes only. NOT_CONFIGURED is NOT a failure. */
    public List<IntelSourceOutcome> failures() {
        List<IntelSourceOutcome> failures = new ArrayList<>();
        for (IntelSourceOutcome outcome : outcomes) {
            if (outcome.status() == IntelSourceStatus.FAILED) {
                failures.add(outcome);
            }
        }
        return List.copyOf(failures);
    }

    public Optional<IntelSourceOutcome> forSource(String sourceName) {
        for (IntelSourceOutcome outcome : outcomes) {
            if (outcome.sourceName().equals(sourceName)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }

    /** Union of cveIds across OK outcomes, deduplicated on canonical CveId, sorted by id. */
    public List<CveId> allCveIds() {
        TreeSet<CveId> union = new TreeSet<>(Comparator.comparing(CveId::id));
        for (IntelSourceOutcome outcome : outcomes) {
            if (outcome.isOk()) {
                union.addAll(outcome.result().cveIds());
            }
        }
        return List.copyOf(union);
    }
}
