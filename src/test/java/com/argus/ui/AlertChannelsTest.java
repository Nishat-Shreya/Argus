package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.core.ScanAlert;
import com.argus.core.ScanCompletionNotice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Section 6.7 of the P3-03 plan: {@link AlertChannels} — composite fan-out + {@code none()}.
 */
class AlertChannelsTest {

    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));

    @Test
    void c1DeliversToEveryChannelOnceEachInOrder() {
        List<String> order = new ArrayList<>();
        AlertChannel first = recording("first", order);
        AlertChannel second = recording("second", order);
        AlertChannel third = recording("third", order);

        AlertChannel composite = AlertChannels.of(List.of(first, second, third));
        composite.deliver(ALERT);

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void c2IsolationOneChannelThrowingDoesNotStopTheOthers() {
        List<String> order = new ArrayList<>();
        AlertChannel throwing = new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
                throw new RuntimeException("boom");
            }

            @Override
            public void close() {
            }
        };
        AlertChannel second = recording("second", order);
        AlertChannel third = recording("third", order);

        AlertChannel composite = AlertChannels.of(List.of(throwing, second, third));
        assertDoesNotThrow(() -> composite.deliver(ALERT));

        assertEquals(List.of("second", "third"), order);
    }

    @Test
    void c3CloseClosesEveryChannelEvenWhenOneThrows() {
        AtomicInteger closedCount = new AtomicInteger();
        AlertChannel throwingClose = new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
            }

            @Override
            public void close() {
                closedCount.incrementAndGet();
                throw new RuntimeException("boom");
            }
        };
        AlertChannel normal = new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
            }

            @Override
            public void close() {
                closedCount.incrementAndGet();
            }
        };

        AlertChannel composite = AlertChannels.of(List.of(throwingClose, normal));
        assertDoesNotThrow(composite::close);

        assertEquals(2, closedCount.get());
    }

    @Test
    void c4CloseIsIdempotent() {
        AtomicInteger closedCount = new AtomicInteger();
        AlertChannel channel = new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
            }

            @Override
            public void close() {
                closedCount.incrementAndGet();
            }
        };

        AlertChannel composite = AlertChannels.of(List.of(channel));
        composite.close();
        composite.close();

        assertEquals(2, closedCount.get());
    }

    @Test
    void c5NoneIsASilentNoOp() {
        AlertChannel none = AlertChannels.none();
        assertDoesNotThrow(() -> none.deliver(ALERT));
        assertDoesNotThrow(() -> none.deliver(null));
        assertDoesNotThrow(none::close);
        assertDoesNotThrow(none::close);
    }

    @Test
    void c6MalformedInputs() {
        assertThrows(NullPointerException.class, () -> AlertChannels.of(null));
        AlertChannel composite = AlertChannels.of(List.of());
        assertDoesNotThrow(() -> composite.deliver(ALERT));
        assertDoesNotThrow(composite::close);
    }

    // ---- scanCompleted: the every-successful-scan path (email only) ---------------------

    private static final ScanCompletionNotice NOTICE = new ScanCompletionNotice("example.com", 3L,
            5, OptionalLong.of(2L), Optional.empty());

    @Test
    void c7ScanCompletedReachesEveryChannelOnceEach() {
        List<String> completed = new ArrayList<>();
        AlertChannel first = completing("first", completed);
        AlertChannel second = completing("second", completed);

        AlertChannels.of(List.of(first, second)).scanCompleted(NOTICE);

        assertEquals(List.of("first", "second"), completed);
    }

    @Test
    void c8ScanCompletedIsolationOneChannelThrowingDoesNotStopTheOthers() {
        List<String> completed = new ArrayList<>();
        AlertChannel throwing = new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
            }

            @Override
            public void scanCompleted(ScanCompletionNotice notice) {
                throw new RuntimeException("boom");
            }

            @Override
            public void close() {
            }
        };

        AlertChannel composite = AlertChannels.of(List.of(throwing, completing("second", completed)));
        assertDoesNotThrow(() -> composite.scanCompleted(NOTICE));

        assertEquals(List.of("second"), completed);
    }

    /** Webhook and desktop implement only {@code deliver}, so they must be untouched by the
     *  new path -- they still fire only on new findings. */
    @Test
    void c9ChannelsThatOnlyImplementDeliverIgnoreScanCompletedAndKeepTheirAlertBehaviour() {
        List<String> alerts = new ArrayList<>();
        AlertChannel alertOnly = recording("alertOnly", alerts);

        AlertChannel composite = AlertChannels.of(List.of(alertOnly));
        composite.scanCompleted(NOTICE);
        assertEquals(List.of(), alerts, "scanCompleted must not reach deliver(ScanAlert)");

        composite.deliver(ALERT);
        assertEquals(List.of("alertOnly"), alerts);
    }

    @Test
    void c10NoneIsASilentNoOpForScanCompletedToo() {
        assertDoesNotThrow(() -> AlertChannels.none().scanCompleted(NOTICE));
        assertDoesNotThrow(() -> AlertChannels.of(List.of()).scanCompleted(NOTICE));
    }

    private static AlertChannel completing(String name, List<String> completed) {
        return new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
            }

            @Override
            public void scanCompleted(ScanCompletionNotice notice) {
                completed.add(name);
            }

            @Override
            public void close() {
            }
        };
    }

    private static AlertChannel recording(String name, List<String> order) {
        return new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
                order.add(name);
            }

            @Override
            public void close() {
            }
        };
    }
}
