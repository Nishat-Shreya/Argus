package com.argus.ui;

/**
 * Invariant 8 for the tag-name field (plan §3.3) — the {@code NoteValidation} /
 * {@code LoginValidation} / {@code DiffValidation} / {@code ApiKeyValidation} twin. Owns
 * normalisation as well as rejection, so {@code ui} and {@code db} agree on what a tag name IS.
 */
final class TagValidation {

    static final int MAX_NAME_CHARS = 40;

    private TagValidation() {
    }

    /**
     * Checked in order: {@code null}/blank &rarr; error; else normalise ({@code strip()}, then
     * collapse every internal whitespace run to a single space); normalised length over
     * {@link #MAX_NAME_CHARS} &rarr; error; else ok with the normalised name.
     */
    static Result check(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Result.error("a tag name must not be empty");
        }
        String normalized = rawName.strip().replaceAll("\\s+", " ");
        if (normalized.length() > MAX_NAME_CHARS) {
            return Result.error("a tag name must be at most " + MAX_NAME_CHARS + " characters");
        }
        return Result.ok(normalized);
    }

    record Result(boolean valid, String message, String name) {
        static Result ok(String normalizedName) {
            return new Result(true, null, normalizedName);
        }

        static Result error(String message) {
            return new Result(false, message, null);
        }
    }
}
