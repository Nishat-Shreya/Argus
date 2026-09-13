package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Extracts an {@link AbuseIpdbReport} from an AbuseIPDB {@code /check} response body (plan
 * §3.6/§3.7). Jackson tree model, never a mapped DTO: every field except "the root is an
 * object" is optional; a missing/renamed/retyped field yields absent, never an exception.
 *
 * {@code reports[].comment}, {@code reports[].reporterId}, {@code reports[].reporterCountryCode}
 * and {@code reports[].reportedAt} are NEVER surfaced (plan §3.6): report comments are
 * operator-submitted free text that in practice contains raw log excerpts from other people's
 * servers, which can carry third-party credentials, paths and tokens.
 */
final class AbuseIpdbResponseParser {

    private AbuseIpdbResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @throws IntelSourceException body blank, not JSON, or not a JSON object
     */
    static AbuseIpdbReport parse(String body) throws IntelSourceException {
        if (body == null || body.isBlank()) {
            throw new IntelSourceException(
                    AbuseIpdbSource.NAME, "AbuseIPDB response body was blank");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IntelSourceException(
                    AbuseIpdbSource.NAME, "AbuseIPDB response body was not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new IntelSourceException(
                    AbuseIpdbSource.NAME, "AbuseIPDB response was not a JSON object");
        }

        JsonNode data = root.path("data");
        JsonNode scoreNode = data.path("abuseConfidenceScore");
        boolean hasData = data.isObject() && scoreNode.isIntegralNumber()
                && scoreNode.asInt() >= 0 && scoreNode.asInt() <= IntelResult.MAX_SCORE;

        JsonNode isPublicNode = data.path("isPublic");
        boolean isPublic = !isPublicNode.isBoolean() || isPublicNode.asBoolean();
        int score = hasData ? scoreNode.asInt() : 0;

        Map<String, String> attributes = buildAttributes(data, hasData);

        return new AbuseIpdbReport(hasData, isPublic, score, attributes);
    }

    private static Map<String, String> buildAttributes(JsonNode data, boolean hasData) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!data.isObject()) {
            return attrs;
        }

        if (hasData) {
            attrs.put("abuse_confidence",
                    String.valueOf(data.path("abuseConfidenceScore").asInt()));
        }

        putIfPositiveInt(attrs, "total_reports", data.path("totalReports"));
        putIfPositiveInt(attrs, "distinct_reporters", data.path("numDistinctUsers"));

        if (hasData) {
            attrs.put("report_window_days", String.valueOf(AbuseIpdbSource.MAX_AGE_IN_DAYS));
        }

        String categories = categoriesSummary(data.path("reports"));
        if (!categories.isEmpty()) {
            attrs.put("categories", truncate(categories));
        }

        putIfText(attrs, "country", countryDisplay(data));
        putIfText(attrs, "isp", textOrNull(data.path("isp")));
        putIfText(attrs, "domain", textOrNull(data.path("domain")));
        putIfText(attrs, "usage_type", textOrNull(data.path("usageType")));

        String hostnames = joinedArray(data.path("hostnames"));
        if (!hostnames.isEmpty()) {
            attrs.put("hostnames", truncate(hostnames));
        }

        if (data.path("isWhitelisted").asBoolean(false)) {
            attrs.put("is_whitelisted", "true");
        }
        if (data.path("isTor").asBoolean(false)) {
            attrs.put("is_tor", "true");
        }
        JsonNode isPublicNode = data.path("isPublic");
        if (isPublicNode.isBoolean() && !isPublicNode.asBoolean()) {
            attrs.put("is_public", "false");
        }

        return attrs;
    }

    /** Distinct codes across every reports[].categories[], ascending by code, mapped to names. */
    private static String categoriesSummary(JsonNode reportsNode) {
        if (!reportsNode.isArray()) {
            return "";
        }
        TreeSet<Integer> codes = new TreeSet<>();
        for (JsonNode report : reportsNode) {
            JsonNode categories = report.path("categories");
            if (categories.isArray()) {
                for (JsonNode code : categories) {
                    if (code.isIntegralNumber()) {
                        codes.add(code.asInt());
                    }
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (int code : codes) {
            names.add(AbuseCategories.nameOf(code));
        }
        return String.join(", ", names);
    }

    private static String countryDisplay(JsonNode data) {
        String name = textOrNull(data.path("countryName"));
        if (name != null) {
            return name;
        }
        return textOrNull(data.path("countryCode"));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String joinedArray(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            if (element.isTextual() && !element.asText().isBlank()) {
                values.add(element.asText());
            }
        }
        return String.join(", ", values);
    }

    private static void putIfPositiveInt(Map<String, String> attrs, String key, JsonNode node) {
        if (node.isIntegralNumber() && node.asInt() > 0) {
            attrs.put(key, String.valueOf(node.asInt()));
        }
    }

    private static void putIfText(Map<String, String> attrs, String key, String value) {
        if (value != null && !value.isBlank()) {
            attrs.put(key, truncate(value));
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
