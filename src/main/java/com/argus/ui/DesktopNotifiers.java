package com.argus.ui;

import java.lang.System.Logger.Level;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Chooses a notifier (plan §3.3). Reads one system property; probes the tray only through a
 * supplier, so a disabled build never loads {@code java.awt} at all.
 */
final class DesktopNotifiers {

    static final String NOTIFICATIONS_PROPERTY = "argus.notifications";

    private static final System.Logger LOGGER = System.getLogger(DesktopNotifiers.class.getName());

    private DesktopNotifiers() {
    }

    /** Production entry point. Called once, by {@code App}, after unlock. */
    static DesktopNotifier create() {
        boolean enabled = !"false".equalsIgnoreCase(System.getProperty(NOTIFICATIONS_PROPERTY));
        return create(enabled, SystemTrayNotifier::isAvailable, SystemTrayNotifier::new);
    }

    /**
     * Test seam — no system property read, no AWT class loaded unless {@code real} is invoked.
     *
     * <p>Short-circuit order matters and is tested (F1): when {@code enabled} is false, {@code
     * trayAvailable} is never called and {@code real} is never invoked, so a disabled build
     * never touches AWT at all. {@code RuntimeException} from either supplier (e.g. {@code
     * HeadlessException}) yields {@link DesktopNotifier#disabled()}; {@code Error} is
     * deliberately not caught (R6).
     */
    static DesktopNotifier create(boolean enabled, BooleanSupplier trayAvailable,
            Supplier<DesktopNotifier> real) {
        if (!enabled) {
            return DesktopNotifier.disabled();
        }
        boolean available;
        try {
            available = trayAvailable.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "desktop tray availability probe failed", e);
            return DesktopNotifier.disabled();
        }
        if (!available) {
            return DesktopNotifier.disabled();
        }
        try {
            return real.get();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "desktop notifier construction failed", e);
            return DesktopNotifier.disabled();
        }
    }
}
