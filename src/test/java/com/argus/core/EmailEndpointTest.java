package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailEndpointTest {

    @Test
    void parseHappyPath() {
        EmailEndpoint endpoint =
                EmailEndpoint.parse("smtp.example.com|465|operator@example.com|ops@example.com|s3cret");

        assertEquals("smtp.example.com", endpoint.host());
        assertEquals(465, endpoint.port());
        assertEquals("operator@example.com", endpoint.username());
        assertEquals("ops@example.com", endpoint.recipient());
        assertEquals("s3cret", endpoint.password());
    }

    @Test
    void aPasswordContainingAPipeIsCapturedWhole() {
        EmailEndpoint endpoint = EmailEndpoint.parse(
                "smtp.example.com|465|operator@example.com|ops@example.com|s3|cr|et");

        assertEquals("s3|cr|et", endpoint.password());
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> EmailEndpoint.parse(""));
        assertThrows(IllegalArgumentException.class, () -> EmailEndpoint.parse(null));
    }

    @Test
    void rejectsTheWrongNumberOfFields() {
        assertThrows(IllegalArgumentException.class,
                () -> EmailEndpoint.parse("smtp.example.com|465|operator@example.com"));
    }

    @Test
    void rejectsANonNumericPort() {
        assertThrows(IllegalArgumentException.class, () -> EmailEndpoint.parse(
                "smtp.example.com|notaport|operator@example.com|ops@example.com|s3cret"));
    }

    @Test
    void rejectsAnOutOfRangePort() {
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                "smtp.example.com", 0, "operator@example.com", "ops@example.com", "s3cret"));
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                "smtp.example.com", 70000, "operator@example.com", "ops@example.com", "s3cret"));
    }

    @Test
    void rejectsARecipientWithNoAtSign() {
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                "smtp.example.com", 465, "operator@example.com", "not-an-email", "s3cret"));
    }

    @Test
    void rejectsABlankHostUsernameOrPassword() {
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                " ", 465, "operator@example.com", "ops@example.com", "s3cret"));
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                "smtp.example.com", 465, " ", "ops@example.com", "s3cret"));
        assertThrows(IllegalArgumentException.class, () -> new EmailEndpoint(
                "smtp.example.com", 465, "operator@example.com", "ops@example.com", " "));
    }

    @Test
    void toStringNeverRevealsUsernamePasswordOrRecipient() {
        EmailEndpoint endpoint = new EmailEndpoint(
                "smtp.example.com", 465, "operator@example.com", "ops@example.com", "s3cret");
        String text = endpoint.toString();

        assertEquals("EmailEndpoint[smtp.example.com:465]", text);
        assertFalse(text.contains("operator"));
        assertFalse(text.contains("ops@example.com"));
        assertFalse(text.contains("s3cret"));
    }
}
