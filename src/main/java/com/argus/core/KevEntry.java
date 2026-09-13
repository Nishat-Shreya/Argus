package com.argus.core;

import java.util.Objects;

/**
 * One entry from the CISA KEV catalog, reduced to the fields Argus uses.
 *
 * NO DATE COMPONENT EXISTS, DELIBERATELY. The feed carries {@code dateAdded} and {@code dueDate};
 * both are dropped at parse time. Argus has exactly one clock — the scans row (P1-04) — and a
 * "days since added to KEV" or "deadline passed" signal would be a second one. Pinned by
 * {@code KevEntryTest}: 5 components, none in the temporal package.
 */
public record KevEntry(
        CveId cveId,
        String vendorProject,
        String product,
        String vulnerabilityName,
        boolean knownRansomwareCampaignUse) {

    static final int MAX_TEXT_LENGTH = 256;

    public KevEntry {
        Objects.requireNonNull(cveId, "cveId must not be null");
        vendorProject = normalize(vendorProject);
        product = normalize(product);
        vulnerabilityName = normalize(vulnerabilityName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TEXT_LENGTH);
        }
        return trimmed;
    }
}
