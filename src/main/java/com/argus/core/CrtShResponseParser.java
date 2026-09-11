package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Extracts raw DNS-name tokens from a crt.sh {@code output=json} response body. */
final class CrtShResponseParser {

    private CrtShResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @return every raw token found in the response, in document order, with duplicates and
     *         out-of-scope names still present. Empty list for an empty body or "[]".
     * @throws SubdomainEnumerationException if the body is not a JSON array of objects
     */
    static List<String> parse(String body) throws SubdomainEnumerationException {
        if (body == null || body.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new SubdomainEnumerationException(
                    "crt.sh response body was not a JSON array", e);
        }

        if (root == null || !root.isArray()) {
            throw new SubdomainEnumerationException(
                    "crt.sh response body was not a JSON array");
        }

        List<String> tokens = new ArrayList<>();
        for (JsonNode element : root) {
            if (!element.isObject()) {
                continue;
            }
            JsonNode nameValue = element.get("name_value");
            if (nameValue != null && nameValue.isTextual()) {
                for (String token : nameValue.asText().split("\r\n|\n")) {
                    tokens.add(token);
                }
            }
            JsonNode commonName = element.get("common_name");
            if (commonName != null && commonName.isTextual()) {
                tokens.add(commonName.asText());
            }
        }
        return tokens;
    }
}
