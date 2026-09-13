package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extracts a {@link ShodanHostReport} from a Shodan {@code /shodan/host/{ip}} response body
 * (plan §3.6/§3.7). Jackson tree model, never a mapped DTO: every field except "the root is an
 * object" is optional; a missing/renamed/retyped field yields absent, never an exception.
 */
final class ShodanResponseParser {

    private ShodanResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @throws IntelSourceException body blank, not JSON, or not a JSON object
     */
    static ShodanHostReport parse(String body) throws IntelSourceException {
        if (body == null || body.isBlank()) {
            throw new IntelSourceException(ShodanSource.NAME, "Shodan response body was blank");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IntelSourceException(
                    ShodanSource.NAME, "Shodan response body was not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new IntelSourceException(ShodanSource.NAME, "Shodan response was not a JSON object");
        }

        boolean hasHostData = root.path("ip_str").isTextual()
                || root.path("ports").isArray()
                || root.path("data").isArray();

        // ---- CVE union walk ----
        // Deduplicated on the canonical CveId (not the raw string), so that the same CVE
        // appearing in different casing across the host-level array and a banner-level
        // vulns object collapses to exactly one entry. CveId is a record over its single
        // canonical `id` field, so its generated equals/hashCode are already structural:
        // a LinkedHashSet<CveId> dedupes by value, not by reference.
        Set<CveId> cveIds = new LinkedHashSet<>();
        collectVulnCandidates(root.path("vulns"), cveIds);

        JsonNode dataArray = root.path("data");
        int verifiedVulnCount = 0;
        if (dataArray.isArray()) {
            for (JsonNode banner : dataArray) {
                JsonNode bannerVulns = banner.path("vulns");
                collectVulnCandidates(bannerVulns, cveIds);
                if (bannerVulns.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = bannerVulns.fields();
                    while (fields.hasNext()) {
                        JsonNode value = fields.next().getValue();
                        if (value.isObject() && value.path("verified").asBoolean(false)) {
                            verifiedVulnCount++;
                        }
                    }
                }
            }
        }

        Map<String, String> attributes =
                buildAttributes(root, dataArray, cveIds.size(), verifiedVulnCount);

        return new ShodanHostReport(hasHostData, new ArrayList<>(cveIds), verifiedVulnCount, attributes);
    }

    /**
     * Both vulns shapes: an array of CVE strings, or an object keyed by CVE id. Each raw token
     * is validated and converted to its canonical {@link CveId} here, before insertion into
     * {@code out} — so dedup happens on the canonical form (e.g. uppercase), not on the raw
     * string. Invalid/malformed tokens are silently dropped, never thrown on.
     */
    private static void collectVulnCandidates(JsonNode vulns, Set<CveId> out) {
        if (vulns.isArray()) {
            for (JsonNode element : vulns) {
                if (element.isTextual() && CveId.isValid(element.asText())) {
                    out.add(new CveId(element.asText()));
                }
            }
        } else if (vulns.isObject()) {
            Iterator<String> fieldNames = vulns.fieldNames();
            while (fieldNames.hasNext()) {
                String candidate = fieldNames.next();
                if (CveId.isValid(candidate)) {
                    out.add(new CveId(candidate));
                }
            }
        }
    }

    private static Map<String, String> buildAttributes(
            JsonNode root, JsonNode dataArray, int vulnCount, int verifiedVulnCount) {
        Map<String, String> attrs = new LinkedHashMap<>();

        List<Integer> ports = topLevelPorts(root);
        if (ports.isEmpty() && dataArray.isArray()) {
            ports = bannerPorts(dataArray);
        }
        if (!ports.isEmpty()) {
            attrs.put("port_count", String.valueOf(ports.size()));
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < ports.size(); i++) {
                if (i > 0) {
                    joined.append(", ");
                }
                joined.append(ports.get(i));
            }
            attrs.put("ports", truncate(joined.toString()));
        }

        if (dataArray.isArray() && !dataArray.isEmpty()) {
            String services = servicesSummary(dataArray);
            if (!services.isEmpty()) {
                attrs.put("services", truncate(services));
            }
        }

        putJoinedArrayIfPresent(attrs, "hostnames", root.path("hostnames"));
        putIfText(attrs, "org", root.path("org"));
        putIfText(attrs, "isp", root.path("isp"));
        putIfText(attrs, "asn", root.path("asn"));
        putIfText(attrs, "os", root.path("os"));
        putIfText(attrs, "country", root.path("country_name"));
        putJoinedArrayIfPresent(attrs, "tags", root.path("tags"));

        if (vulnCount > 0) {
            attrs.put("vuln_count", String.valueOf(vulnCount));
        }
        if (verifiedVulnCount > 0) {
            attrs.put("verified_vulns", String.valueOf(verifiedVulnCount));
        }

        return attrs;
    }

    private static List<Integer> topLevelPorts(JsonNode root) {
        JsonNode portsNode = root.path("ports");
        TreeSet<Integer> distinct = new TreeSet<>();
        if (portsNode.isArray()) {
            for (JsonNode element : portsNode) {
                if (element.isNumber()) {
                    distinct.add(element.asInt());
                }
            }
        }
        return new ArrayList<>(distinct);
    }

    private static List<Integer> bannerPorts(JsonNode dataArray) {
        TreeSet<Integer> distinct = new TreeSet<>();
        for (JsonNode banner : dataArray) {
            JsonNode port = banner.path("port");
            if (port.isNumber()) {
                distinct.add(port.asInt());
            }
        }
        return new ArrayList<>(distinct);
    }

    private static String servicesSummary(JsonNode dataArray) {
        // Ascending by port, as the plan specifies.
        List<JsonNode> banners = new ArrayList<>();
        dataArray.forEach(banners::add);
        banners.sort((a, b) -> Integer.compare(a.path("port").asInt(0), b.path("port").asInt(0)));

        List<String> summaries = new ArrayList<>();
        for (JsonNode banner : banners) {
            JsonNode portNode = banner.path("port");
            if (!portNode.isNumber()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(portNode.asInt());
            JsonNode transport = banner.path("transport");
            sb.append('/').append(transport.isTextual() ? transport.asText() : "tcp");
            JsonNode product = banner.path("product");
            if (product.isTextual() && !product.asText().isBlank()) {
                sb.append(' ').append(product.asText());
            }
            JsonNode version = banner.path("version");
            if (version.isTextual() && !version.asText().isBlank()) {
                sb.append(' ').append(version.asText());
            }
            summaries.add(sb.toString());
        }
        return String.join(", ", summaries);
    }

    private static void putJoinedArrayIfPresent(Map<String, String> attrs, String key, JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            if (element.isTextual() && !element.asText().isBlank()) {
                values.add(element.asText());
            }
        }
        if (!values.isEmpty()) {
            attrs.put(key, truncate(String.join(", ", values)));
        }
    }

    private static void putIfText(Map<String, String> attrs, String key, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            attrs.put(key, truncate(node.asText()));
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
