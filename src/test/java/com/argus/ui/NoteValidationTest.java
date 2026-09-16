package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Invariant 8 for the note field (plan §6.4) — the {@code LoginValidation} /
 *  {@code DashboardValidation} / {@code DiffValidation} / {@code ApiKeyValidation} twin. */
class NoteValidationTest {

    @Test
    void nullBodyIsInvalidAndNamesEmpty() {
        NoteValidation.Result result = NoteValidation.check(null);
        assertFalse(result.valid());
        assertTrue(result.message().contains("empty"));
    }

    @Test
    void emptyBodyIsInvalid() {
        assertFalse(NoteValidation.check("").valid());
    }

    @Test
    void whitespaceOnlyBodyIsInvalid() {
        assertFalse(NoteValidation.check("   \t\n ").valid());
    }

    @Test
    void aBodyWithLeadingAndTrailingWhitespaceIsValidAndStripped() {
        NoteValidation.Result result = NoteValidation.check("  already patched  ");
        assertTrue(result.valid());
        assertEquals("already patched", result.body());
    }

    @Test
    void lineEndingsAreNormalizedToLf() {
        NoteValidation.Result result = NoteValidation.check("a\r\nb\rc");
        assertTrue(result.valid());
        assertEquals("a\nb\nc", result.body());
    }

    @Test
    void aBodyOfExactlyTheMaxLengthIsValid() {
        String body = "x".repeat(NoteValidation.MAX_BODY_CHARS);
        assertTrue(NoteValidation.check(body).valid());
    }

    @Test
    void aBodyOneOverTheMaxLengthIsInvalidAndNamesTheLimit() {
        String body = "x".repeat(NoteValidation.MAX_BODY_CHARS + 1);
        NoteValidation.Result result = NoteValidation.check(body);
        assertFalse(result.valid());
        assertTrue(result.message().contains(String.valueOf(NoteValidation.MAX_BODY_CHARS)));
    }

    @Test
    void aBodyThatStripsDownToTheMaxLengthIsValid() {
        String padded = " " + "x".repeat(NoteValidation.MAX_BODY_CHARS);
        assertEquals(NoteValidation.MAX_BODY_CHARS + 1, padded.length());
        NoteValidation.Result result = NoteValidation.check(padded);
        assertTrue(result.valid());
        assertEquals(NoteValidation.MAX_BODY_CHARS, result.body().length());
    }

    @Test
    void aBodyContainingHtmlIsValidAndUnchanged() {
        String body = "<script>alert(1)</script>";
        NoteValidation.Result result = NoteValidation.check(body);
        assertTrue(result.valid());
        assertEquals(body, result.body());
    }
}
