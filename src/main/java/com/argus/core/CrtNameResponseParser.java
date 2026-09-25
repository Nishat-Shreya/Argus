package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts raw DNS-name tokens from a crt.name {@code /v1/search?apex=...&format=json} body:
 * a JSON array of objects, each carrying the name in {@code "sub"} -- for example
 * {@code [{"sub":"kuet.ac.bd"},{"sub":"mail.kuet.ac.bd"}]}. Optional extra fields (such as
 * {@code first_seen} when the API is asked for dates) are ignored.
 *
 * JSON-shape concerns only: every textual {@code sub} is returned untouched, in response
 * order, duplicates and all. DNS validity, scope and deduplication belong to
 * {@link SubdomainNormalizer}. Anything that is not a JSON array -- an HTML error page, a
 * plain-text error, a JSON object -- is an error, never silently "no subdomains".
 */
final class CrtNameResponseParser {

    private CrtNameResponseParser() {}

    // Thread-safe once configured; constructing one per call is a known performance mistake.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @return the raw {@code sub} tokens; empty for a blank body or an empty array
     * @throws SubdomainEnumerationException the body was not a JSON array
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
                    "crt.name response body was not a JSON array", e);
        }

        if (root == null || !root.isArray()) {
            throw new SubdomainEnumerationException(
                    "crt.name response body was not a JSON array");
        }

        List<String> tokens = new ArrayList<>();
        for (JsonNode element : root) {
            if (!element.isObject()) {
                continue;
            }
            JsonNode sub = element.get("sub");
            if (sub != null && sub.isTextual()) {
                tokens.add(sub.asText());
            }
        }
        return tokens;
    }
}
