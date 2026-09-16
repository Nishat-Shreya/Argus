package com.argus.ui;

/**
 * Invariant 8 for the note field (plan §3.3) — the {@code LoginValidation} /
 * {@code DashboardValidation} / {@code DiffValidation} / {@code ApiKeyValidation} twin. Owns
 * normalisation as well as rejection, so {@code ui} and {@code db} agree on what "blank" means.
 */
final class NoteValidation {

    static final int MAX_BODY_CHARS = 2000;

    private NoteValidation() {
    }

    /**
     * Checked in order: {@code null}/blank &rarr; error; else normalise (line endings to
     * {@code \n}, then {@link String#strip()}); normalised length over {@link #MAX_BODY_CHARS}
     * &rarr; error; else ok with the normalised body.
     */
    static Result check(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Result.error("a note must not be empty");
        }
        String normalized = rawBody.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (normalized.length() > MAX_BODY_CHARS) {
            return Result.error("a note must be at most " + MAX_BODY_CHARS + " characters");
        }
        return Result.ok(normalized);
    }

    record Result(boolean valid, String message, String body) {
        static Result ok(String normalizedBody) {
            return new Result(true, null, normalizedBody);
        }

        static Result error(String message) {
            return new Result(false, message, null);
        }
    }
}
