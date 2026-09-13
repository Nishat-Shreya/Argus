package com.argus.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable snapshot of the CISA KEV catalog, indexed for O(1) lookup.
 *
 * THE INDEX IS KEYED ON CANONICAL {@link CveId}, NEVER ON A RAW STRING. This is the exact place
 * P2-03's and P2-05's reviewer-found dedup bug would recur.
 *
 * Immutable and thread-safe: all fields final, the map defensively copied via {@code Map.copyOf}.
 * Holds no clock — {@code catalogVersion} is an OPAQUE provenance label, never parsed, never
 * compared, and never propagated onto a {@link KevMatchResult}.
 */
public final class KevCatalog {

    private final String catalogVersion;
    private final Map<CveId, KevEntry> entries;

    public KevCatalog(String catalogVersion, Collection<KevEntry> entries) {
        this.catalogVersion = Objects.requireNonNull(catalogVersion, "catalogVersion must not be null");
        Objects.requireNonNull(entries, "entries must not be null");

        Map<CveId, KevEntry> byId = new LinkedHashMap<>();
        for (KevEntry entry : entries) {
            Objects.requireNonNull(entry, "entries must not contain a null element");
            byId.put(entry.cveId(), entry);
        }
        this.entries = Map.copyOf(byId);
    }

    /** Empty catalog, version "". Every lookup misses; a valid state, not an error. */
    public static KevCatalog empty() {
        return new KevCatalog("", List.of());
    }

    public Optional<KevEntry> find(CveId cveId) {
        return Optional.ofNullable(entries.get(cveId));
    }

    public boolean contains(CveId cveId) {
        return entries.containsKey(cveId);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Opaque provenance label, e.g. "2026.09.11". NEVER parsed as a date. */
    public String catalogVersion() {
        return catalogVersion;
    }

    /** All entries, sorted by CVE id. Defensive copy. */
    public List<KevEntry> entries() {
        List<KevEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing(e -> e.cveId().id()));
        return List.copyOf(sorted);
    }
}
