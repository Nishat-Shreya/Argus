package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 6.1: the operator id identity rules -- canonical, filename-safe, traversal-proof. */
class OperatorIdTest {

    @Test
    void acceptsASimpleId() {
        assertEquals("nishat", OperatorId.of("nishat").value());
    }

    @Test
    void canonicalisesCaseAndWhitespace() {
        assertEquals(OperatorId.of("nishat"), OperatorId.of("  Nishat  "));
    }

    @Test
    void acceptsDotDashUnderscoreAndDigits() {
        assertEquals("op.1_a-b", OperatorId.of("op.1_a-b").value());
    }

    @Test
    void rejectsNullBlankAndWhitespaceOnly() {
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(null));
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(""));
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of("   "));

        assertFalse(OperatorId.isValid(null));
        assertFalse(OperatorId.isValid(""));
        assertFalse(OperatorId.isValid("   "));
    }

    @Test
    void rejectsTooShortAndTooLong() {
        IllegalArgumentException tooShort =
                assertThrows(IllegalArgumentException.class, () -> OperatorId.of("ab"));
        assertTrue(tooShort.getMessage().contains(String.valueOf(OperatorId.MIN_LENGTH)));

        String tooLongValue = "a".repeat(33);
        IllegalArgumentException tooLong =
                assertThrows(IllegalArgumentException.class, () -> OperatorId.of(tooLongValue));
        assertTrue(tooLong.getMessage().contains(String.valueOf(OperatorId.MAX_LENGTH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../x", "a/b", "a\\b", "C:x", ".", "a..b"})
    void rejectsPathSeparatorsAndTraversal(String candidate) {
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(candidate));
        assertFalse(OperatorId.isValid(candidate));
    }

    @Test
    void rejectsNonAsciiAndControlCharacters() {
        String nonAscii = "nisśat";
        String controlChar = "a" + (char) 0 + "b";
        String embeddedSpace = "a b";

        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(nonAscii));
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(controlChar));
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(embeddedSpace));
    }

    @ParameterizedTest
    @ValueSource(strings = {".ab", "-ab", "ab.", "ab-"})
    void rejectsLeadingPunctuationAndTrailingDotOrHyphen(String candidate) {
        assertThrows(IllegalArgumentException.class, () -> OperatorId.of(candidate));
    }

    @Test
    void fileNameIsDerivedAndHasNoSeparator() {
        String fileName = OperatorId.of("nishat").fileName();
        assertEquals("nishat.vault.json", fileName);
        assertFalse(fileName.contains("/"));
        assertFalse(fileName.contains("\\"));
        assertFalse(fileName.contains(".."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nishat", "op.1_a-b", "a23", "x.y_z-9"})
    void fileNameResolvesInsideTheDirectory(String raw) {
        Path dir = Path.of("some", "vault", "dir");
        OperatorId id = OperatorId.of(raw);
        Path resolved = dir.resolve(id.fileName()).normalize();
        assertTrue(resolved.startsWith(dir));
    }
}
