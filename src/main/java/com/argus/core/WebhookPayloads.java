package com.argus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the JSON body for one {@link ScanAlert}. Pure, exact-value testable, no reflection, no
 * annotations — explicit {@link ObjectNode} construction, the project's Jackson idiom, so field
 * order is fixed and assertable. There is no {@code timestamp} field, deliberately: P3-01's /
 * P3-02's "no new clock read" rule holds for this item too.
 */
final class WebhookPayloads {

    static final String EVENT = "scan.findings.added";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookPayloads() {
    }

    static String toJson(ScanAlert alert) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("source", "argus");
        root.put("event", EVENT);
        root.put("target", alert.target());
        root.put("baselineScanId", alert.baselineScanId());
        root.put("currentScanId", alert.currentScanId());
        root.put("addedCount", alert.addedCount());
        ArrayNode subjects = root.putArray("addedSubjects");
        for (String subject : alert.addedSubjects()) {
            subjects.add(subject);
        }
        root.put("subjectsTruncated", alert.subjectsTruncated());
        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // ObjectNode of only strings/longs/ints/booleans/arrays thereof cannot fail to
            // serialize; this branch exists only to satisfy the checked-exception signature.
            throw new IllegalStateException("failed to serialize webhook payload", e);
        }
    }
}
