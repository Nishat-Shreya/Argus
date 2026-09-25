package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CrtNameResponseParser} extracts raw DNS-name tokens from a crt.name
 * {@code /v1/search?...&format=json} body -- {@code [{"sub":"host.example.com"}, ...]} -- JSON-shape
 * concerns only, no DNS/scope filtering (that is {@link SubdomainNormalizer}). Jackson tree model,
 * tolerant of extra fields such as {@code first_seen}.
 */
class CrtNameResponseParserTest {

    @Test
    void parsesFixtureIntoRawTokensInResponseOrder() throws Exception {
        List<String> tokens = CrtNameResponseParser.parse(Fixtures.read("example-com.json"));

        // The parser filters nothing: out-of-scope names, emails, wildcards and raw-case /
        // trailing-dot / padded tokens all pass through untouched, duplicates included.
        assertEquals(List.of(
                "example.com", "www.example.com", "*.example.com", "API.Example.COM",
                "mail.example.com.", "dev.example.com", "assets.example.com",
                "legacy.example.com", "cdn.othercorp.net", "admin@example.com",
                "notexample.com", "example.com.evil.test", "www.example.com",
                "  api.example.com  "), tokens);
    }

    @Test
    void parsesTheExactShapeCrtNameReturns() throws Exception {
        assertEquals(List.of("kuet.ac.bd", "mail.kuet.ac.bd"), CrtNameResponseParser.parse(
                "[{\"sub\":\"kuet.ac.bd\"},{\"sub\":\"mail.kuet.ac.bd\"}]\n"));
    }

    @Test
    void ignoresTheOptionalFirstSeenDates() throws Exception {
        assertEquals(List.of("kuet.ac.bd"), CrtNameResponseParser.parse(
                "[{\"first_seen\":\"2010-02-08T22:51:49Z\",\"sub\":\"kuet.ac.bd\"}]"));
    }

    @Test
    void emptyArrayYieldsEmptyList() throws Exception {
        assertEquals(List.of(), CrtNameResponseParser.parse(Fixtures.read("empty-array.json")));
    }

    @Test
    void emptyOrBlankBodyYieldsEmptyList() throws Exception {
        assertEquals(List.of(), CrtNameResponseParser.parse(""));
        assertEquals(List.of(), CrtNameResponseParser.parse("   "));
        assertEquals(List.of(), CrtNameResponseParser.parse(null));
    }

    @Test
    void toleratesMissingNullAndOddlyTypedFieldsKeepingOnlyTextualSubs() {
        List<String> tokens = assertDoesNotThrow(
                () -> CrtNameResponseParser.parse(Fixtures.read("missing-fields.json")));

        assertEquals(List.of("with-date.example.com", "extra-field.example.com"), tokens);
    }

    @Test
    void htmlErrorBodyThrows() {
        SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                () -> CrtNameResponseParser.parse(Fixtures.read("bad-gateway.html")));
        assertTrue(e.getMessage().contains("crt.name"));
        assertTrue(e.getMessage().toLowerCase().contains("json array"));
    }

    @Test
    void plainTextErrorBodyThrows() {
        // What crt.name sends with a 400 -- the enumerator rejects the status before parsing,
        // but the parser must still refuse it rather than invent names.
        assertThrows(SubdomainEnumerationException.class, () -> CrtNameResponseParser.parse(
                "invalid apex: not an apex (eTLD+1 is kuet.ac.bd)\n"));
    }

    @Test
    void nonArrayJsonThrows() {
        assertThrows(SubdomainEnumerationException.class,
                () -> CrtNameResponseParser.parse("{\"error\":\"nope\"}"));
        assertThrows(SubdomainEnumerationException.class,
                () -> CrtNameResponseParser.parse("\"just a string\""));
    }
}
