package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 6.3 of the P3-03 plan: {@link WebhookPayloads}. Pure, exact-value testable, no
 * reflection, no annotations, no clock (P6).
 */
class WebhookPayloadsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void p1HappyPathExactJson() {
        ScanAlert alert = new ScanAlert("example.com", 41L, 42L, 2,
                List.of("api.example.com", "10.0.0.1:8080"));
        String json = WebhookPayloads.toJson(alert);
        assertEquals("{\"source\":\"argus\",\"event\":\"scan.findings.added\","
                + "\"target\":\"example.com\",\"baselineScanId\":41,\"currentScanId\":42,"
                + "\"addedCount\":2,\"addedSubjects\":[\"api.example.com\",\"10.0.0.1:8080\"],"
                + "\"subjectsTruncated\":false}", json);
    }

    @Test
    void p2TruncatedFlagIsTrueWhenCapBites() {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < ScanAlert.MAX_SUBJECTS; i++) {
            subjects.add("host" + i + ".example.com");
        }
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 62, subjects);
        String json = WebhookPayloads.toJson(alert);
        assertTrue(json.contains("\"subjectsTruncated\":true"));
    }

    @Test
    void p3EscapingRoundTrips() throws Exception {
        String tricky = "quote\" backslash\\ non-ascii café";
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 1, List.of(tricky));
        String json = WebhookPayloads.toJson(alert);
        JsonNode node = MAPPER.readTree(json);
        assertEquals(tricky, node.path("addedSubjects").get(0).asText());
    }

    @Test
    void p4VocabularyPassthroughAndNoPortOrOpenLiteral() throws Exception {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 2,
                List.of("10.0.0.1:8080", "api.example.com"));
        String json = WebhookPayloads.toJson(alert);
        assertTrue(json.contains("10.0.0.1:8080"));
        assertTrue(json.contains("api.example.com"));
        assertFalse(json.contains("PORT"));
        assertFalse(json.contains("OPEN"));

        String source = readSource();
        assertFalse(source.contains("\"PORT\""));
        assertFalse(source.contains("\"OPEN\""));
    }

    @Test
    void p5BodyIsValidJsonFieldByField() throws Exception {
        ScanAlert alert = new ScanAlert("example.com", 41L, 42L, 3,
                List.of("a.example.com", "b.example.com", "c.example.com"));
        String json = WebhookPayloads.toJson(alert);
        JsonNode node = MAPPER.readTree(json);
        assertEquals("argus", node.path("source").asText());
        assertEquals("scan.findings.added", node.path("event").asText());
        assertEquals("example.com", node.path("target").asText());
        assertEquals(41L, node.path("baselineScanId").asLong());
        assertEquals(42L, node.path("currentScanId").asLong());
        assertEquals(3, node.path("addedCount").asInt());
        assertTrue(node.path("addedSubjects").isArray());
        assertEquals(3, node.path("addedSubjects").size());
        assertFalse(node.path("subjectsTruncated").asBoolean());
    }

    @Test
    void p6NoClockFields() {
        ScanAlert alert = new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));
        String json = WebhookPayloads.toJson(alert);
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("timestamp"));
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("\"time\""));
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("\"date\""));
    }

    private static String readSource() throws Exception {
        return java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/argus/core/WebhookPayloads.java"));
    }
}
