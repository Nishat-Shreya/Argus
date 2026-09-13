package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jackson TREE-MODEL parse of the KEV feed. Tolerant by design: the live feed already carries
 * an undocumented field ({@code forensicTriage}) the published schema does not mention.
 *
 * NEVER READS {@code dateAdded}, {@code dueDate} or {@code dateReleased} — see {@link KevEntry}'s
 * Javadoc. Reading them, even to discard the value, would be the clock rule's spirit violated
 * even if not its letter, so this parser never even path()s into them.
 */
final class KevCatalogParser {

    private KevCatalogParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static KevCatalog parse(String json) throws KevCatalogException {
        if (json == null || json.isBlank()) {
            throw new KevCatalogException("the CISA KEV feed response body was blank");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new KevCatalogException("the CISA KEV feed response body was not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new KevCatalogException("the CISA KEV feed response was not a JSON object");
        }

        JsonNode vulnerabilities = root.path("vulnerabilities");
        if (!vulnerabilities.isArray()) {
            throw new KevCatalogException(
                    "the CISA KEV feed response had no \"vulnerabilities\" array");
        }

        String catalogVersion = textOrEmpty(root.path("catalogVersion"));

        List<KevEntry> entries = new ArrayList<>();
        for (JsonNode node : vulnerabilities) {
            String rawCveId = textOrNull(node.path("cveID"));
            if (rawCveId == null || !CveId.isValid(rawCveId)) {
                continue;
            }
            CveId cveId = new CveId(rawCveId);
            String vendorProject = textOrEmpty(node.path("vendorProject"));
            String product = textOrEmpty(node.path("product"));
            String vulnerabilityName = textOrEmpty(node.path("vulnerabilityName"));
            boolean ransomware =
                    isRansomwareKnown(textOrNull(node.path("knownRansomwareCampaignUse")));
            entries.add(new KevEntry(cveId, vendorProject, product, vulnerabilityName, ransomware));
        }

        return new KevCatalog(catalogVersion, entries);
    }

    /**
     * "Known" (trimmed, case-insensitive) -> true; everything else, including absent, null,
     * "Unknown" and any future third value -> false. Never throws.
     */
    static boolean isRansomwareKnown(String raw) {
        if (raw == null) {
            return false;
        }
        return raw.trim().equalsIgnoreCase("Known");
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static String textOrEmpty(JsonNode node) {
        return node.isTextual() ? node.asText() : "";
    }
}
