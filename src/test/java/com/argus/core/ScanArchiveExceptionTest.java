package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** T's driver for plan §3.4: the one additive {@code String}-only constructor, needed for
 *  "no scan with id N" (a real condition, not a persistence failure with a cause). */
class ScanArchiveExceptionTest {

    @Test
    void aMessageOnlyConstructorCarriesTheMessageAndNoCause() {
        ScanArchiveException exception = new ScanArchiveException("no scan with id 42");

        assertEquals("no scan with id 42", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void theExistingMessageAndCauseConstructorIsUnchanged() {
        Exception cause = new Exception("boom");
        ScanArchiveException exception = new ScanArchiveException("saving failed", cause);

        assertEquals("saving failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}
