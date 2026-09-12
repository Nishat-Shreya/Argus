package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ApiKeyValue} — what a storable API key value may be (plan §6.2).
 */
class ApiKeyValueTest {

    private static final String HEX_64 = "a".repeat(64);

    @Test
    void normalizeStripsSurroundingWhitespace() {
        assertEquals("abc", ApiKeyValue.normalize("  abc  "));
    }

    @Test
    void normalizeOfNullIsNull() {
        assertNull(ApiKeyValue.normalize(null));
    }

    @Test
    void normalizeOfAlreadyCleanValueIsIdentity() {
        assertEquals("abc", ApiKeyValue.normalize("abc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "A-Za_za.09-ABC",
            "abc123+/=="
    })
    void acceptsValidShapes(String candidate) {
        assertTrue(ApiKeyValue.isValid(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlank(String candidate) {
        assertFalse(ApiKeyValue.isValid(candidate));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(ApiKeyValue.isValid(null));
        assertFalse(ApiKeyValue.isValid(""));
    }

    @Test
    void rejectsCrlfInjection() {
        assertFalse(ApiKeyValue.isValid("key\r\nX-Evil: 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab\ncd", "ab\0cd", "ab\tcd", "ab cd", "clé"})
    void rejectsControlInteriorSpaceAndNonAscii(String candidate) {
        assertFalse(ApiKeyValue.isValid(candidate));
    }

    @Test
    void exactlyMaxLengthIsAccepted() {
        String value = "a".repeat(ApiKeyValue.MAX_LENGTH);
        assertTrue(ApiKeyValue.isValid(value));
    }

    @Test
    void oneOverMaxLengthIsRejected() {
        String value = "a".repeat(ApiKeyValue.MAX_LENGTH + 1);
        assertFalse(ApiKeyValue.isValid(value));
    }

    @Test
    void surroundingWhitespaceDoesNotInvalidateAnOtherwiseValidKey() {
        assertTrue(ApiKeyValue.isValid("  " + HEX_64 + "  \n"));
    }

    @Test
    void requireReturnsTheNormalizedValueWhenValid() {
        assertEquals(HEX_64, ApiKeyValue.require("  " + HEX_64 + "  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab cd", "ab\ncd", "ab\tcd"})
    void requireThrowsOnInvalidInput(String candidate) {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyValue.require(candidate));
    }

    @Test
    void requireThrowsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyValue.require(null));
    }

    // --- invariant 7: no exception message may echo any part of the candidate value ---

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "ab cd efgh",
            "zqxv7mfk\nzqxv7mfk",
            "zqxv7mfk\rzqxv7mfk",
            "zqxv7mfk\0zqxv7mfk",
            "zqxv7mfk\tzqxv7mfk",
            "clé-zqxv7mfk-nonascii",
    })
    void requireMessageNeverContainsASubstringOfTheInputOfLengthFourOrMore(String candidate) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ApiKeyValue.require(candidate));
        String message = ex.getMessage();
        String normalized = candidate == null ? "" : candidate;
        for (int i = 0; i + 4 <= normalized.length(); i++) {
            String substring = normalized.substring(i, i + 4);
            assertFalse(message.contains(substring),
                    "message leaked a substring of the candidate value: " + substring);
        }
    }

    @Test
    void overlongInputMessageDoesNotContainTheValue() {
        String overlong = "z".repeat(ApiKeyValue.MAX_LENGTH + 50);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ApiKeyValue.require(overlong));
        assertFalse(ex.getMessage().contains(overlong));
    }

    @Test
    void declaresOnlyStaticFinalFieldsAndNoLogger() {
        for (Field field : ApiKeyValue.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
                    "field " + field.getName() + " must be static final");
            assertFalse(field.getType().getName().contains("Logger"),
                    "field " + field.getName() + " must not be a logger");
        }
    }

    @Test
    void sanityIsValidNeverThrows() {
        assertDoesNotThrow(() -> ApiKeyValue.isValid(null));
    }
}
