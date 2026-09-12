package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Extracts a {@link VirusTotalReport} from a VirusTotal v3 domain/IP reputation response body
 * (plan §3.5). Jackson tree model, never a mapped DTO: every field except {@code data.attributes}
 * itself is optional, and a missing/renamed/retyped field yields "absent", never an exception.
 */
final class VirusTotalResponseParser {

    private VirusTotalResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @throws IntelSourceException  body blank, not JSON, or has no data.attributes object
     */
    static VirusTotalReport parse(String body, IntelSubjectKind kind) throws IntelSourceException {
        if (body == null || body.isBlank()) {
            throw new IntelSourceException(
                    VirusTotalSource.NAME, "VirusTotal response body was blank");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IntelSourceException(
                    VirusTotalSource.NAME, "VirusTotal response body was not valid JSON", e);
        }

        JsonNode attributes = root == null ? null : root.path("data").path("attributes");
        if (attributes == null || !attributes.isObject()) {
            throw new IntelSourceException(VirusTotalSource.NAME,
                    "VirusTotal response had no data.attributes object");
        }

        JsonNode stats = attributes.path("last_analysis_stats");
        boolean statsPresent = stats.isObject();
        int harmless = intOrZero(stats, "harmless");
        int malicious = intOrZero(stats, "malicious");
        int suspicious = intOrZero(stats, "suspicious");
        int undetected = intOrZero(stats, "undetected");
        int timeout = intOrZero(stats, "timeout");

        Map<String, String> attrs = new LinkedHashMap<>();
        if (statsPresent) {
            int analyzed = harmless + malicious + suspicious + undetected;
            int flagged = malicious + suspicious;
            attrs.put("detection_ratio", flagged + "/" + analyzed);
            attrs.put("analysis_stats", "malicious=" + malicious + ", suspicious=" + suspicious
                    + ", harmless=" + harmless + ", undetected=" + undetected
                    + ", timeout=" + timeout);
        }

        JsonNode reputation = attributes.path("reputation");
        if (reputation.isNumber()) {
            attrs.put("reputation", reputation.asText());
        }

        JsonNode totalVotes = attributes.path("total_votes");
        if (totalVotes.isObject()) {
            int votesHarmless = intOrZero(totalVotes, "harmless");
            int votesMalicious = intOrZero(totalVotes, "malicious");
            attrs.put("community_votes",
                    "harmless=" + votesHarmless + ", malicious=" + votesMalicious);
        }

        JsonNode tags = attributes.path("tags");
        if (tags.isArray() && !tags.isEmpty()) {
            List<String> tagList = new ArrayList<>();
            for (JsonNode tag : tags) {
                if (tag.isTextual()) {
                    tagList.add(tag.asText());
                }
            }
            if (!tagList.isEmpty()) {
                attrs.put("tags", truncate(String.join(", ", tagList)));
            }
        }

        JsonNode categories = attributes.path("categories");
        if (categories.isObject() && !categories.isEmpty()) {
            TreeSet<String> distinctValues = new TreeSet<>();
            categories.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    distinctValues.add(entry.getValue().asText());
                }
            });
            if (!distinctValues.isEmpty()) {
                attrs.put("categories", truncate(String.join(", ", distinctValues)));
            }
        }

        if (kind == IntelSubjectKind.DOMAIN) {
            putIfText(attrs, "registrar", attributes.path("registrar"));
        } else {
            putIfNumber(attrs, "asn", attributes.path("asn"));
            putIfText(attrs, "as_owner", attributes.path("as_owner"));
            putIfText(attrs, "country", attributes.path("country"));
            putIfText(attrs, "network", attributes.path("network"));
        }

        return new VirusTotalReport(harmless, malicious, suspicious, undetected, timeout, attrs);
    }

    private static int intOrZero(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : 0;
    }

    private static void putIfText(Map<String, String> attrs, String key, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            attrs.put(key, truncate(node.asText()));
        }
    }

    private static void putIfNumber(Map<String, String> attrs, String key, JsonNode node) {
        if (node.isNumber()) {
            attrs.put(key, node.asText());
        }
    }

    /** Truncates to IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH with a trailing "…" on overflow. */
    private static String truncate(String value) {
        if (value.length() <= IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH - 1) + "…";
    }
}
