package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Plan §6.4: {@link TagValidation}, invariant 8 for the tag-name field. */
class TagValidationTest {

    @Test
    void nullNameIsInvalidAndNamesEmpty() {
        TagValidation.Result result = TagValidation.check(null);
        assertFalse(result.valid());
        assertTrue(result.message().contains("empty"));
    }

    @Test
    void emptyAndWhitespaceOnlyAreInvalid() {
        assertFalse(TagValidation.check("").valid());
        assertFalse(TagValidation.check("   \t\n ").valid());
    }

    @Test
    void surroundingWhitespaceIsStripped() {
        TagValidation.Result result = TagValidation.check("  prod  ");
        assertTrue(result.valid());
        assertEquals("prod", result.name());
    }

    @Test
    void internalWhitespaceRunsAreCollapsedToASingleSpace() {
        TagValidation.Result result = TagValidation.check("web \n server");
        assertTrue(result.valid());
        assertEquals("web server", result.name());
    }

    @Test
    void namePreservesCase() {
        TagValidation.Result result = TagValidation.check("Prod");
        assertTrue(result.valid());
        assertEquals("Prod", result.name());
    }

    @Test
    void exactlyMaxCharsIsValidAndOneMoreIsInvalid() {
        String maxName = "x".repeat(TagValidation.MAX_NAME_CHARS);
        assertTrue(TagValidation.check(maxName).valid());

        String tooLong = "x".repeat(TagValidation.MAX_NAME_CHARS + 1);
        TagValidation.Result result = TagValidation.check(tooLong);
        assertFalse(result.valid());
        assertTrue(result.message().contains(String.valueOf(TagValidation.MAX_NAME_CHARS)));
    }

    @Test
    void lengthCheckRunsOnTheNormalisedName() {
        String rawTooLongButNormalisesToMax =
                "  " + "x".repeat(TagValidation.MAX_NAME_CHARS) + "  ";
        assertTrue(rawTooLongButNormalisesToMax.length() > TagValidation.MAX_NAME_CHARS);
        TagValidation.Result result = TagValidation.check(rawTooLongButNormalisesToMax);
        assertTrue(result.valid());
        assertEquals(TagValidation.MAX_NAME_CHARS, result.name().length());
    }

    @Test
    void htmlLikeInputIsValidAndUnchanged() {
        String htmlLike = "<script>alert(1)</script>";
        TagValidation.Result result = TagValidation.check(htmlLike);
        assertTrue(result.valid());
        assertEquals(htmlLike, result.name());
    }
}
