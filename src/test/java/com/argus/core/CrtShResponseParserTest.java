package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CrtShResponseParser} extracts raw DNS-name tokens from a crt.sh {@code output=json}
 * body — JSON-shape concerns only, no DNS/scope filtering (that is {@link SubdomainNormalizer}).
 * Jackson tree model, tolerant of the API's explicitly unstable shape (plan §3.3/§7.1).
 */
class CrtShResponseParserTest {

    @Test
    void parsesFixtureIntoRawTokens() throws Exception {
        List<String> tokens = CrtShResponseParser.parse(Fixtures.read("example-com.json"));

        // The parser filters nothing: out-of-scope SANs, emails and raw-case/trailing-dot
        // tokens all pass through untouched.
        assertTrue(tokens.contains("example.com"));
        assertTrue(tokens.contains("www.example.com"));
        assertTrue(tokens.contains("*.example.com"));
        assertTrue(tokens.contains("API.Example.COM"));
        assertTrue(tokens.contains("mail.example.com."));
        assertTrue(tokens.contains("cdn.othercorp.net"));
        assertTrue(tokens.contains("admin@example.com"));
        assertTrue(tokens.contains("notexample.com"));
        assertTrue(tokens.contains("example.com.evil.test"));
    }

    @Test
    void splitsNewlineSeparatedNameValue() throws Exception {
        String body = "[{\"name_value\":\"a.example.com\\nb.example.com\\nc.example.com\"}]";
        List<String> tokens = CrtShResponseParser.parse(body);
        assertEquals(3, tokens.size());
        assertTrue(tokens.contains("a.example.com"));
        assertTrue(tokens.contains("b.example.com"));
        assertTrue(tokens.contains("c.example.com"));
    }

    @Test
    void splitsCrlfSeparatedNameValue() throws Exception {
        String body = "[{\"name_value\":\"a.example.com\\r\\nb.example.com\"}]";
        List<String> tokens = CrtShResponseParser.parse(body);
        assertEquals(2, tokens.size());
        assertTrue(tokens.contains("a.example.com"));
        assertTrue(tokens.contains("b.example.com"));
    }

    @Test
    void includesCommonNameNotPresentInNameValue() throws Exception {
        List<String> tokens = CrtShResponseParser.parse(Fixtures.read("example-com.json"));
        assertTrue(tokens.contains("dev.example.com"));
    }

    @Test
    void emptyArrayYieldsEmptyList() throws Exception {
        assertEquals(List.of(), CrtShResponseParser.parse(Fixtures.read("empty-array.json")));
    }

    @Test
    void emptyOrBlankBodyYieldsEmptyList() throws Exception {
        assertEquals(List.of(), CrtShResponseParser.parse(""));
        assertEquals(List.of(), CrtShResponseParser.parse("   "));
        assertEquals(List.of(), CrtShResponseParser.parse(null));
    }

    @Test
    void toleratesMissingNullAndOddlyTypedFields() {
        List<String> tokens = assertDoesNotThrow(
                () -> CrtShResponseParser.parse(Fixtures.read("missing-fields.json")));
        assertTrue(tokens.contains("cn-only.example.com"));
        assertTrue(tokens.contains("null-name-value.example.com"));
        assertTrue(tokens.contains("numeric-name-value.example.com"));
        assertTrue(tokens.contains("extra-field.example.com"));
    }

    @Test
    void htmlErrorBodyThrows() {
        SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                () -> CrtShResponseParser.parse(Fixtures.read("bad-gateway.html")));
        assertTrue(e.getMessage().toLowerCase().contains("json array"));
    }

    @Test
    void nonArrayJsonThrows() {
        assertThrows(SubdomainEnumerationException.class,
                () -> CrtShResponseParser.parse("{\"error\":\"nope\"}"));
        assertThrows(SubdomainEnumerationException.class,
                () -> CrtShResponseParser.parse("\"just a string\""));
    }
}
