package com.argus.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Matches CVE ids against a CISA KEV catalog snapshot and scores the result.
 *
 * PURE FUNCTION OBJECT: no I/O, no mutable state, no locks, no threads, no clock. Immutable and
 * safe for concurrent use by any number of scan threads.
 *
 * The catalog is injected fully built. There is deliberately NO lazy load, NO refresh(), NO TTL
 * and NO {@code volatile KevCatalog} field — a lazy {@code if (catalog == null) catalog =
 * load();} would be exactly the check-then-act that invariant 5 forbids. To pick up a newer
 * catalog, build a new {@code KevScorer}.
 */
public final class KevScorer {

    private final KevCatalog catalog;

    public KevScorer(KevCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /**
     * @param cveIds any collection of CveId — typically IntelReport.allCveIds(). Duplicates are
     *               tolerated and collapse on canonical CveId. An empty collection is a normal
     *               input and yields KevMatchResult.none().
     */
    public KevMatchResult score(Collection<CveId> cveIds) {
        Objects.requireNonNull(cveIds, "cveIds must not be null");
        // Duplicate input CveIds are tolerated and collapse before reaching KevMatchResult's
        // constructor, which treats a duplicate cveId as the double-counting bug it would be
        // for anything OTHER than the caller's own input repeating itself (plan §3.4, test S7).
        Map<CveId, KevEntry> byId = new LinkedHashMap<>();
        for (CveId cveId : cveIds) {
            Objects.requireNonNull(cveId, "cveIds must not contain a null element");
            Optional<KevEntry> entry = catalog.find(cveId);
            entry.ifPresent(e -> byId.put(e.cveId(), e));
        }
        return new KevMatchResult(List.copyOf(byId.values()));
    }

    public boolean isKnownExploited(CveId cveId) {
        return catalog.contains(cveId);
    }

    public KevCatalog catalog() {
        return catalog;
    }
}
