package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-scan guard (the project's no-toolkit, no-network convention: {@code SubdomainScanJob}
 * builds its own real {@code SubdomainEnumerator}, which a unit test in this package cannot stub).
 * {@code ScanCoordinator} deliberately never echoes {@code getMessage()} into the scan log, so the
 * real crt.name failure reason must be reported by the job itself -- and the exception must still
 * be rethrown, so the scan's status logic is untouched.
 */
class SubdomainScanJobWiringTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/argus/ui/SubdomainScanJob.java"));
    }

    @Test
    void theJobReportsTheRealEnumerationErrorAndRethrowsIt() throws IOException {
        String source = source();

        int catchIndex = source.indexOf("catch (SubdomainEnumerationException e)");
        assertTrue(catchIndex >= 0, "the job must catch SubdomainEnumerationException");

        String handler = source.substring(catchIndex);
        int logIndex = handler.indexOf("LOGGER.log(Level.WARNING");
        int rethrowIndex = handler.indexOf("throw e;");
        assertTrue(logIndex >= 0, "the failure reason must be logged");
        assertTrue(handler.contains("e.getMessage()"), "the log must carry the actual message");
        assertTrue(rethrowIndex > logIndex, "the exception must be rethrown after logging");
    }

    @Test
    void theJobNeverFallsBackToAnotherSource() throws IOException {
        String source = source();

        assertEquals(1, occurrences(source, "enumerator.enumerate("),
                "exactly one enumeration call, no retry or alternative source");
        assertTrue(!source.contains("crt.sh"));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
