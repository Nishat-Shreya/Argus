package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ApiKeyValue;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ApiKeyValidation} — invariant 8 at the field, the {@code LoginValidation} twin
 * (plan §6.5). No javafx.* imports.
 */
class ApiKeyValidationTest {

    private static final String VALID_KEY =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void nullIsInvalidAndMentionsThatAKeyIsRequired() {
        ApiKeyValidation.Result result = ApiKeyValidation.check(null);
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIsInvalidAndMentionsThatAKeyIsRequired(String candidate) {
        ApiKeyValidation.Result result = ApiKeyValidation.check(candidate);
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("required"));
    }

    @Test
    void controlCharacterInputIsInvalidAndMentionsControlCharacters() {
        ApiKeyValidation.Result result = ApiKeyValidation.check("abc\ndef");
        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("control"));
    }

    @Test
    void overLongInputIsInvalidAndNamesMaxLength() {
        String overlong = "a".repeat(ApiKeyValue.MAX_LENGTH + 1);
        ApiKeyValidation.Result result = ApiKeyValidation.check(overlong);
        assertFalse(result.valid());
        assertTrue(result.message().contains(String.valueOf(ApiKeyValue.MAX_LENGTH)));
    }

    @Test
    void aValidKeyIsValidWithNullMessage() {
        ApiKeyValidation.Result result = ApiKeyValidation.check(VALID_KEY);
        assertTrue(result.valid());
        assertNull(result.message());
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        ApiKeyValidation.Result result = ApiKeyValidation.check("  " + VALID_KEY + "  ");
        assertTrue(result.valid());
    }

    @Test
    void resultHasExactlyTwoComponents() {
        RecordComponent[] components = ApiKeyValidation.Result.class.getRecordComponents();
        assertTrue(components.length == 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab cd efgh", "zqxv7mfk\nzqxv7mfk", "zqxv7mfk\tzqxv7mfk"})
    void messageNeverContainsASubstringOfTheInputOfLengthFourOrMore(String candidate) {
        ApiKeyValidation.Result result = ApiKeyValidation.check(candidate);
        assertFalse(result.valid());
        String message = result.message();
        for (int i = 0; i + 4 <= candidate.length(); i++) {
            String substring = candidate.substring(i, i + 4);
            assertFalse(message.contains(substring),
                    "message leaked a substring of the candidate value: " + substring);
        }
    }
}
