package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.argus.core.ScanAlert;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 6.7 of the P3-03 plan: {@link DesktopAlertChannel} — adapts P3-02's
 * {@link DesktopNotifier} with zero changes to it.
 */
class DesktopAlertChannelTest {

    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));

    @Test
    void d1DeliverCallsShowOnceWithTheExactDesktopNotification() {
        List<DesktopNotification> shown = new ArrayList<>();
        DesktopNotifier notifier = new DesktopNotifier() {
            @Override
            public void show(DesktopNotification notification) {
                shown.add(notification);
            }

            @Override
            public void close() {
            }
        };

        new DesktopAlertChannel(notifier).deliver(ALERT);

        assertEquals(1, shown.size());
        assertEquals(ScanNotifications.desktopNotification(ALERT), shown.get(0));
    }

    @Test
    void d2ANotifierThatThrowsIsCaught() {
        DesktopNotifier notifier = new DesktopNotifier() {
            @Override
            public void show(DesktopNotification notification) {
                throw new RuntimeException("boom");
            }

            @Override
            public void close() {
            }
        };

        assertDoesNotThrow(() -> new DesktopAlertChannel(notifier).deliver(ALERT));
    }

    @Test
    void d3CloseDoesNotCloseTheWrappedNotifier() {
        List<String> events = new ArrayList<>();
        DesktopNotifier notifier = new DesktopNotifier() {
            @Override
            public void show(DesktopNotification notification) {
            }

            @Override
            public void close() {
                events.add("closed");
            }
        };

        new DesktopAlertChannel(notifier).close();

        assertFalse(events.contains("closed"));
    }
}
