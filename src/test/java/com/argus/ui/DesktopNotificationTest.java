package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Section 6.1: exact-value tests for {@link DesktopNotification}'s validation and truncation.
 * AWT-free, JavaFX-free.
 */
class DesktopNotificationTest {

    @Test
    void t1HappyPathRoundTripsUnchanged() {
        DesktopNotification notification = new DesktopNotification("Argus · 1 new finding",
                "example.com · api.example.com");
        assertEquals("Argus · 1 new finding", notification.caption());
        assertEquals("example.com · api.example.com", notification.text());
    }

    @Test
    void t2NullCaptionOrTextThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new DesktopNotification(null, "text"));
        assertThrows(IllegalArgumentException.class,
                () -> new DesktopNotification("caption", null));
    }

    @Test
    void t3BlankCaptionOrTextThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new DesktopNotification("   ", "text"));
        assertThrows(IllegalArgumentException.class,
                () -> new DesktopNotification("caption", "   "));
    }

    @Test
    void t4BoundaryCaptionIsUntouched() {
        String caption = "c".repeat(DesktopNotification.MAX_CAPTION_CHARS);
        DesktopNotification notification = new DesktopNotification(caption, "text");
        assertEquals(caption, notification.caption());
    }

    @Test
    void t5TruncationEndsInEllipsis() {
        String caption = "c".repeat(DesktopNotification.MAX_CAPTION_CHARS + 1);
        DesktopNotification notification = new DesktopNotification(caption, "text");
        String expectedCaption =
                "c".repeat(DesktopNotification.MAX_CAPTION_CHARS - 1) + "…";
        assertEquals(expectedCaption, notification.caption());
        assertEquals(DesktopNotification.MAX_CAPTION_CHARS, notification.caption().length());

        String text = "t".repeat(DesktopNotification.MAX_TEXT_CHARS + 1);
        DesktopNotification textNotification = new DesktopNotification("caption", text);
        String expectedText = "t".repeat(DesktopNotification.MAX_TEXT_CHARS - 1) + "…";
        assertEquals(expectedText, textNotification.text());
        assertEquals(DesktopNotification.MAX_TEXT_CHARS, textNotification.text().length());
    }

    @Test
    void t6EqualCaptionAndTextAreEqualRecords() {
        DesktopNotification a = new DesktopNotification("caption", "text");
        DesktopNotification b = new DesktopNotification("caption", "text");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
