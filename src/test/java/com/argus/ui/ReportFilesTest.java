package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 7.2: {@code ReportFiles} -- the blocking write, isolated into its own class so it is
 * unit-testable against {@code @TempDir} rather than being unreachable inside a {@code Task}
 * lambda (plan §3.4).
 */
class ReportFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void f1WritesAndRoundTripsASimpleDocument() throws IOException {
        Path file = tempDir.resolve("report.html");

        ReportFiles.write(file, "<!DOCTYPE html><html></html>");

        assertEquals("<!DOCTYPE html><html></html>",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void f2Utf8IsExplicitRegardlessOfPlatformDefaultCharset() throws IOException {
        Path file = tempDir.resolve("report-utf8.html");
        String html = "middle dot · and em dash —";

        ReportFiles.write(file, html);

        assertEquals(html, Files.readString(file, StandardCharsets.UTF_8));
        byte[] expectedBytes = html.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = Files.readAllBytes(file);
        assertEquals(expectedBytes.length, actualBytes.length);
        for (int i = 0; i < expectedBytes.length; i++) {
            assertEquals(expectedBytes[i], actualBytes[i], "byte mismatch at index " + i);
        }
    }

    @Test
    void f3OverwritingAnExistingLongerFileTruncates() throws IOException {
        Path file = tempDir.resolve("report-overwrite.html");
        ReportFiles.write(file, "a much, much longer document than the second write");

        ReportFiles.write(file, "short");

        assertEquals("short", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void f4APathInANonExistentDirectoryThrowsIoException() {
        Path file = tempDir.resolve("missing-dir").resolve("report.html");

        IOException thrown =
                assertThrows(IOException.class, () -> ReportFiles.write(file, "<html></html>"));
        assertEquals(true, thrown.getMessage() != null && !thrown.getMessage().isEmpty());
    }

    @Test
    void f5WritesAndRoundTripsRawBytes() throws IOException {
        Path file = tempDir.resolve("report.pdf");
        byte[] bytes = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);

        ReportFiles.write(file, bytes);

        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void f6OverwritingAnExistingLongerBinaryFileTruncates() throws IOException {
        Path file = tempDir.resolve("report-overwrite.pdf");
        ReportFiles.write(file, "a much, much longer document than the second write"
                .getBytes(StandardCharsets.US_ASCII));

        byte[] shortBytes = "short".getBytes(StandardCharsets.US_ASCII);
        ReportFiles.write(file, shortBytes);

        assertArrayEquals(shortBytes, Files.readAllBytes(file));
    }
}
