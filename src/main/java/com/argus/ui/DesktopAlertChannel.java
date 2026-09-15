package com.argus.ui;

import com.argus.core.ScanAlert;
import java.lang.System.Logger.Level;

/** Adapts P3-02's {@link DesktopNotifier}. ZERO changes to {@code DesktopNotifier}/{@code
 *  SystemTrayNotifier}. */
final class DesktopAlertChannel implements AlertChannel {

    private static final System.Logger LOGGER =
            System.getLogger(DesktopAlertChannel.class.getName());

    private final DesktopNotifier notifier;

    DesktopAlertChannel(DesktopNotifier notifier) {
        this.notifier = notifier;
    }

    /** {@code notifier.show(ScanNotifications.desktopNotification(alert))} — still on the FX
     *  thread, which is what {@code SystemTrayNotifier}'s confinement contract requires. */
    @Override
    public void deliver(ScanAlert alert) {
        try {
            notifier.show(ScanNotifications.desktopNotification(alert));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "desktop notifier threw during deliver()", e);
        }
    }

    /** NO-OP BY DESIGN: {@code App} owns the notifier's lifetime and closes it in {@code stop()}
     *  (P3-02 §3.7). This channel borrows it. Documented so nobody "fixes" it into a double
     *  close. */
    @Override
    public void close() {
        // intentionally does not close the wrapped notifier
    }
}
