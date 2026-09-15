package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.core.ScanAlert;
import java.util.ArrayList;
import java.util.List;
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
