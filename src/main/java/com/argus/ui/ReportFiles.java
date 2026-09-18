package com.argus.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Writes a rendered {@link HtmlReport} document to disk (plan §3.4). BLOCKING -- callers run it
 * off the FX thread, on the {@code argus-report-export} daemon thread. Its own class, rather
 * than an inline lambda inside a {@code Task}, so the write itself is directly testable against
 * {@code @TempDir} (encoding, truncation, overwrite).
 */
final class ReportFiles {

    private ReportFiles() {
    }

    /** Writes {@code html} to {@code file}, UTF-8, creating or truncating. */
    static void write(Path file, String html) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(html, "html");
        Files.writeString(file, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** Writes raw {@code bytes} to {@code file}, creating or truncating (P3-18: a PDF's bytes
     *  are already its exact final encoding -- no charset applies). */
    static void write(Path file, byte[] bytes) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bytes, "bytes");
        Files.write(file, bytes, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
