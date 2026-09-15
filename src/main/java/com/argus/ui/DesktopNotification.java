package com.argus.ui;

/**
 * One notification's text, already validated and length-capped (plan §3.1). AWT-free,
 * JavaFX-free.
 *
 * Truncation happens HERE, in the compact constructor, so an over-long value is
 * unconstructible rather than silently cut by the OS: a string longer than its cap becomes
 * {@code cap - 1} characters followed by {@code "…"}.
 */
record DesktopNotification(String caption, String text) {

    static final int MAX_CAPTION_CHARS = 63; // Windows balloon title limit
    static final int MAX_TEXT_CHARS = 255; // Windows balloon body limit

    DesktopNotification {
        if (caption == null || caption.isBlank()) {
            throw new IllegalArgumentException("caption must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        caption = truncate(caption, MAX_CAPTION_CHARS);
        text = truncate(text, MAX_TEXT_CHARS);
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 1) + "…";
    }
}
