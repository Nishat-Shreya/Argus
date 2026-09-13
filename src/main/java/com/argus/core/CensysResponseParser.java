package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extracts a {@link CensysAssetReport} from a Censys Platform web property or host response
 * body (plan §3.6/§3.7). Jackson tree model, never a mapped DTO: every field except "the root
 * is an object" is optional; a missing/renamed/retyped field yields absent, never an exception.
 *
 * Payload location is ALWAYS {@code result.resource} (plan §1.1(f)) — a decoy top-level
 * {@code resource} field is ignored.
 */
final class CensysResponseParser {

    private CensysResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] EXPOSURE_ARRAYS = {"vulns", "exposures", "misconfigs"};
    private static final String COMPROMISE_ARRAY = "compromises";

    /**
     * @throws IntelSourceException body blank, not JSON, or not a JSON object
     */
    static CensysAssetReport parse(IntelSubjectKind kind, String body) throws IntelSourceException {
        if (body == null || body.isBlank()) {
            throw new IntelSourceException(CensysSource.NAME, "Censys response body was blank");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IntelSourceException(
                    CensysSource.NAME, "Censys response body was not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new IntelSourceException(CensysSource.NAME, "Censys response was not a JSON object");
        }

        JsonNode resource = root.path("result").path("resource");

        boolean hasResource;
        if (kind == IntelSubjectKind.DOMAIN) {
            hasResource = resource.isObject() && isNonBlankText(resource.path("hostname"));
        } else {
            hasResource = resource.isObject() && isNonBlankText(resource.path("ip"));
        }

        RiskWalk walk = new RiskWalk();
        if (resource.isObject()) {
            if (kind == IntelSubjectKind.DOMAIN) {
                walkAssetRisks(resource, walk);
            } else {
                JsonNode services = resource.path("services");
                if (services.isArray()) {
                    for (JsonNode service : services) {
                        walkAssetRisks(service, walk);
                    }
                }
            }
        }

        Map<String, String> attributes = kind == IntelSubjectKind.DOMAIN
                ? buildWebPropertyAttributes(resource, walk)
                : buildHostAttributes(resource, walk);

        return new CensysAssetReport(hasResource, walk.exposureCount(), walk.compromiseCount(),
                new ArrayList<>(walk.cveIds), attributes);
    }

    /** Walks the four risk arrays (vulns/exposures/misconfigs/compromises) on ONE node — either
     *  the web property resource itself, or one host service. */
    private static void walkAssetRisks(JsonNode node, RiskWalk walk) {
        for (String arrayName : EXPOSURE_ARRAYS) {
            walkRiskArray(node.path(arrayName), arrayName, walk);
        }
        walkRiskArray(node.path(COMPROMISE_ARRAY), COMPROMISE_ARRAY, walk);
    }

    private static void walkRiskArray(JsonNode array, String arrayName, RiskWalk walk) {
        if (!array.isArray()) {
            return;
        }
        boolean isCompromise = arrayName.equals(COMPROMISE_ARRAY);
        for (JsonNode risk : array) {
            walk.rawCounts.merge(arrayName, 1, Integer::sum);

            CensysRiskSeverity severity = CensysRiskSeverity.of(textOrNull(risk.path("severity")));
            walk.severityCounts.merge(severity, 1, Integer::sum);

            String id = textOrNull(risk.path("id"));
            if (id == null) {
                if (isCompromise) {
                    walk.compromiseBlankCount++;
                } else {
                    walk.exposureBlankCount++;
                }
                continue;
            }

            String canonicalId = canonicalRiskId(id);
            if (isCompromise) {
                walk.compromiseIds.add(canonicalId);
            } else {
                walk.exposureIds.add(canonicalId);
            }

            if (CveId.isValid(id)) {
                CveId cveId = new CveId(id);
                walk.cveIds.add(cveId);
                if (arrayName.equals("vulns")) {
                    JsonNode kev = risk.path("kev");
                    if (kev.isArray() && !kev.isEmpty()) {
                        walk.kevCveIds.add(cveId);
                    }
                }
            }
        }
    }

    private static Map<String, String> buildWebPropertyAttributes(JsonNode resource, RiskWalk walk) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!resource.isObject()) {
            return attrs;
        }

        putIfText(attrs, "hostname", textOrNull(resource.path("hostname")));
        JsonNode port = resource.path("port");
        if (port.isIntegralNumber()) {
            attrs.put("port", String.valueOf(port.asInt()));
        }
        putIfText(attrs, "tls_version", textOrNull(resource.path("tls").path("version_selected")));
        putIfText(attrs, "cert_issuer",
                textOrNull(resource.path("cert").path("parsed").path("issuer_dn")));
        putIfText(attrs, "cert_sha256", textOrNull(resource.path("cert").path("fingerprint_sha256")));

        String software = softwareSummary(resource.path("software"));
        if (!software.isEmpty()) {
            attrs.put("software", truncate(software));
        }

        String labels = labelsSummary(resource.path("labels"));
        if (!labels.isEmpty()) {
            attrs.put("labels", truncate(labels));
        }

        JsonNode endpoints = resource.path("endpoints");
        if (endpoints.isArray() && !endpoints.isEmpty()) {
            attrs.put("endpoint_count", String.valueOf(endpoints.size()));
        }

        putRiskCounts(attrs, walk);
        putRiskSeverities(attrs, walk);
        putKevCount(attrs, walk);

        String threats = threatsSummary(resource.path("threats"));
        if (!threats.isEmpty()) {
            attrs.put("threats", truncate(threats));
        }

        return attrs;
    }

    private static Map<String, String> buildHostAttributes(JsonNode resource, RiskWalk walk) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!resource.isObject()) {
            return attrs;
        }

        putIfText(attrs, "ip", textOrNull(resource.path("ip")));

        JsonNode services = resource.path("services");
        String ports = portsSummary(services);
        if (!ports.isEmpty()) {
            attrs.put("ports", truncate(ports));
        }
        String protocols = protocolsSummary(services);
        if (!protocols.isEmpty()) {
            attrs.put("services", truncate(protocols));
        }

        JsonNode serviceCount = resource.path("service_count");
        if (serviceCount.isIntegralNumber()) {
            attrs.put("service_count", String.valueOf(serviceCount.asInt()));
        }

        JsonNode asn = resource.path("autonomous_system").path("asn");
        if (asn.isIntegralNumber()) {
            attrs.put("asn", String.valueOf(asn.asLong()));
        }
        String asName = textOrNull(resource.path("autonomous_system").path("name"));
        if (asName == null) {
            asName = textOrNull(resource.path("autonomous_system").path("organization"));
        }
        putIfText(attrs, "as_name", asName);

        String country = textOrNull(resource.path("location").path("country"));
        if (country == null) {
            country = textOrNull(resource.path("location").path("country_code"));
        }
        putIfText(attrs, "country", country);

        String os = operatingSystemSummary(resource.path("operating_system"));
        if (!os.isEmpty()) {
            attrs.put("operating_system", truncate(os));
        }

        String labels = labelsSummary(resource.path("labels"));
        if (!labels.isEmpty()) {
            attrs.put("labels", truncate(labels));
        }

        putIfText(attrs, "reputation_level", textOrNull(resource.path("reputation").path("score_level")));

        putRiskCounts(attrs, walk);
        putRiskSeverities(attrs, walk);
        putKevCount(attrs, walk);

        return attrs;
    }

    private static void putRiskCounts(Map<String, String> attrs, RiskWalk walk) {
        int vulns = walk.rawCounts.getOrDefault("vulns", 0);
        int exposures = walk.rawCounts.getOrDefault("exposures", 0);
        int misconfigs = walk.rawCounts.getOrDefault("misconfigs", 0);
        int compromises = walk.rawCounts.getOrDefault(COMPROMISE_ARRAY, 0);
        if (vulns == 0 && exposures == 0 && misconfigs == 0 && compromises == 0) {
            return;
        }
        attrs.put("risk_counts", "vulns=" + vulns + ", exposures=" + exposures
                + ", misconfigs=" + misconfigs + ", compromises=" + compromises);
    }

    private static void putRiskSeverities(Map<String, String> attrs, RiskWalk walk) {
        int critical = walk.severityCounts.getOrDefault(CensysRiskSeverity.CRITICAL, 0);
        int high = walk.severityCounts.getOrDefault(CensysRiskSeverity.HIGH, 0);
        int medium = walk.severityCounts.getOrDefault(CensysRiskSeverity.MEDIUM, 0);
        int low = walk.severityCounts.getOrDefault(CensysRiskSeverity.LOW, 0);
        if (critical == 0 && high == 0 && medium == 0 && low == 0) {
            return;
        }
        java.util.List<String> parts = new ArrayList<>();
        if (critical > 0) {
            parts.add("critical=" + critical);
        }
        if (high > 0) {
            parts.add("high=" + high);
        }
        if (medium > 0) {
            parts.add("medium=" + medium);
        }
        if (low > 0) {
            parts.add("low=" + low);
        }
        attrs.put("risk_severities", String.join(", ", parts));
    }

    private static void putKevCount(Map<String, String> attrs, RiskWalk walk) {
        if (!walk.kevCveIds.isEmpty()) {
            attrs.put("kev_count", String.valueOf(walk.kevCveIds.size()));
        }
    }

    private static String softwareSummary(JsonNode softwareArray) {
        if (!softwareArray.isArray() || softwareArray.isEmpty()) {
            return "";
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode software : softwareArray) {
            StringBuilder sb = new StringBuilder();
            appendIfText(sb, software.path("vendor"));
            appendIfText(sb, software.path("product"));
            appendIfText(sb, software.path("version"));
            if (sb.length() > 0) {
                distinct.add(sb.toString().trim());
            }
        }
        return String.join(", ", distinct);
    }

    private static String labelsSummary(JsonNode labelsArray) {
        if (!labelsArray.isArray() || labelsArray.isEmpty()) {
            return "";
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode label : labelsArray) {
            String value = textOrNull(label.path("value"));
            if (value != null) {
                distinct.add(value);
            }
        }
        return String.join(", ", distinct);
    }

    private static String threatsSummary(JsonNode threatsArray) {
        if (!threatsArray.isArray() || threatsArray.isEmpty()) {
            return "";
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode threat : threatsArray) {
            String name = textOrNull(threat.path("name"));
            if (name != null) {
                distinct.add(name);
            }
        }
        return String.join(", ", distinct);
    }

    private static String portsSummary(JsonNode servicesArray) {
        if (!servicesArray.isArray()) {
            return "";
        }
        TreeSet<Integer> distinct = new TreeSet<>();
        for (JsonNode service : servicesArray) {
            JsonNode port = service.path("port");
            if (port.isIntegralNumber()) {
                distinct.add(port.asInt());
            }
        }
        java.util.List<String> parts = new ArrayList<>();
        for (int port : distinct) {
            parts.add(String.valueOf(port));
        }
        return String.join(", ", parts);
    }

    private static String protocolsSummary(JsonNode servicesArray) {
        if (!servicesArray.isArray()) {
            return "";
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode service : servicesArray) {
            String protocol = textOrNull(service.path("protocol"));
            if (protocol != null) {
                distinct.add(protocol);
            }
        }
        return String.join(", ", distinct);
    }

    private static String operatingSystemSummary(JsonNode osNode) {
        if (!osNode.isObject()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendIfText(sb, osNode.path("vendor"));
        appendIfText(sb, osNode.path("product"));
        return sb.toString().trim();
    }

    private static void appendIfText(StringBuilder sb, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(node.asText());
        }
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /**
     * Canonicalizes a raw risk {@code id} before it enters the exposure/compromise dedup sets:
     * trim + uppercase, mirroring {@link CveId}'s own canonical form. Risk ids are not always
     * CVE-shaped (Censys mints its own risk identifiers too), so this cannot delegate to
     * {@link CveId} directly — but it must apply the same case-insensitive dedup discipline,
     * otherwise "cve-2021-44228" and "CVE-2021-44228" count as two distinct findings instead of
     * one (the P2-03 bug class, recurring here on the risk-id channel).
     */
    private static String canonicalRiskId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
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

    /** Mutable accumulator for one parse() call — a scratch pad, never shared across calls. */
    private static final class RiskWalk {
        final Map<String, Integer> rawCounts = new LinkedHashMap<>();
        final Map<CensysRiskSeverity, Integer> severityCounts = new EnumMap<>(CensysRiskSeverity.class);
        final Set<String> exposureIds = new LinkedHashSet<>();
        final Set<String> compromiseIds = new LinkedHashSet<>();
        final Set<CveId> cveIds = new LinkedHashSet<>();
        final Set<CveId> kevCveIds = new LinkedHashSet<>();
        int exposureBlankCount;
        int compromiseBlankCount;

        int exposureCount() {
            return exposureIds.size() + exposureBlankCount;
        }

        int compromiseCount() {
            return compromiseIds.size() + compromiseBlankCount;
        }
    }
}
