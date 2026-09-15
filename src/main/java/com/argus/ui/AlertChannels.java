package com.argus.ui;

import com.argus.core.ScanAlert;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/** Composite + fallback. */
final class AlertChannels {

    private static final System.Logger LOGGER = System.getLogger(AlertChannels.class.getName());

    private AlertChannels() {
    }

    /**
     * Fan-out. Delivers to every channel in order; a channel that throws despite the contract
     * is logged and CANNOT prevent the remaining channels from being delivered to (R6 of
     * P3-02, applied to fan-out). {@code close()} closes every channel even if one throws.
     */
    static AlertChannel of(List<AlertChannel> channels) {
        Objects.requireNonNull(channels, "channels");
        List<AlertChannel> copy = List.copyOf(channels);
        if (copy.isEmpty()) {
            return none();
        }
        return new CompositeAlertChannel(copy);
    }

    /** Accepts everything, delivers nothing — the {@code DesktopNotifier.disabled()} shape. */
    static AlertChannel none() {
        return new AlertChannel() {
            @Override
            public void deliver(ScanAlert alert) {
                // no-op: nothing is configured
            }

            @Override
            public void close() {
                // no-op: nothing was ever opened
            }
        };
    }

    private static final class CompositeAlertChannel implements AlertChannel {

        private final List<AlertChannel> channels;

        CompositeAlertChannel(List<AlertChannel> channels) {
            this.channels = channels;
        }

        @Override
        public void deliver(ScanAlert alert) {
            for (AlertChannel channel : channels) {
                try {
                    channel.deliver(alert);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "alert channel threw during deliver(); "
                            + "the remaining channels still receive the alert", e);
                }
            }
        }

        @Override
        public void close() {
            for (AlertChannel channel : channels) {
                try {
                    channel.close();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "alert channel threw during close(); "
                            + "the remaining channels are still closed", e);
                }
            }
        }
    }
}
