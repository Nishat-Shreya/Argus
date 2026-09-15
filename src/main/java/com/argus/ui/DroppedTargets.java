package com.argus.ui;

import com.argus.core.DomainName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every rule for turning a drop into candidate targets. Pure except {@link #read(List)}, which
 * is the only method that touches the filesystem and is contractually forbidden from the FX
 * thread (plan §3.4, §4.2).
 */
final class DroppedTargets {

    static final int MAX_FILES = 8;
    static final long MAX_FILE_BYTES = 1L * 1024 * 1024;
    static final int MAX_TOKENS = 5_000;
    static final int MAX_ECHO_CHARS = 40;
    static final int MAX_REJECTED_SAMPLES = 3;

    private DroppedTargets() {
    }

    /** Pure. The dropped paths when this is a file drop, else empty. Files WIN over text. */
    static List<Path> filesOf(DroppedContent content) {
        return content.hasFiles() ? content.files() : List.of();
    }

    /** Pure. The dropped text when this is a text-only drop with non-blank text, else "". */
    static String textOf(DroppedContent content) {
        if (content.hasFiles()) {
            return "";
        }
        return content.hasText() && !content.text().isBlank() ? content.text() : "";
    }

    /**
     * Pure. Splits on {@code [\s,;]+}, drops empty tokens, runs each through
     * {@link DomainName#normalize} -- the EXISTING rule, not a second one -- keeps normalized
     * accepted values in first-seen order with duplicates removed, and collects rejected tokens
     * truncated to {@link #MAX_ECHO_CHARS}. Stops after {@link #MAX_TOKENS} tokens.
     */
    static TargetDrop parse(String blob) {
        if (blob == null || blob.isBlank()) {
            return TargetDrop.empty();
        }
        String[] tokens = blob.trim().split("[\\s,;]+");

        List<String> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();

        int scanned = 0;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (scanned >= MAX_TOKENS) {
                break;
            }
            scanned++;
            try {
                String normalized = DomainName.normalize(token);
                if (seen.add(normalized)) {
                    accepted.add(normalized);
                }
            } catch (IllegalArgumentException e) {
                rejected.add(echo(token));
            }
        }
        return new TargetDrop(accepted, rejected);
    }

    private static String echo(String token) {
        return token.length() > MAX_ECHO_CHARS ? token.substring(0, MAX_ECHO_CHARS) : token;
    }

    /**
     * BLOCKING FILESYSTEM I/O -- must never be called on the FX Application Thread (invariant
     * 3). Reads at most {@link #MAX_FILES} paths in dragboard order, each rejected outright if
     * larger than {@link #MAX_FILE_BYTES}, decoded as UTF-8 with malformed bytes replaced
     * (never throwing on binary content), joined with '\n'. {@code IOException} messages name
     * {@code path.getFileName()} only, never the full path.
     */
    static String read(List<Path> files) throws IOException {
        List<Path> capped = files.size() > MAX_FILES ? files.subList(0, MAX_FILES) : files;

        StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (Path path : capped) {
            long size;
            try {
                size = Files.size(path);
            } catch (IOException e) {
                throw new IOException("could not read " + path.getFileName() + ": "
                        + e.getMessage(), e);
            }
            if (size > MAX_FILE_BYTES) {
                throw new IOException(
                        path.getFileName() + " is larger than " + MAX_FILE_BYTES + " bytes");
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new IOException("could not read " + path.getFileName() + ": "
                        + e.getMessage(), e);
            }
            if (!first) {
                joined.append('\n');
            }
            joined.append(new String(bytes, StandardCharsets.UTF_8));
            first = false;
        }
        return joined.toString();
    }
}
