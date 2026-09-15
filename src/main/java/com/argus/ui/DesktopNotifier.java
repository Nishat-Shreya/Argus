package com.argus.ui;

/**
 * A place to send one short operator-facing message (plan §3.2). Implementations must never
 * throw, never block, and never require a display to exist.
 *
 * <p>NOTE THE METHOD NAME: {@code show(...)}, not {@code notify(...)}. {@code Object.notify()}
 * is {@code final}; an interface method named {@code notify} would be a permanent reader trap
 * next to this project's {@code wait()}/{@code notify()} discipline (invariant 4). Do not
 * rename it.
 */
interface DesktopNotifier extends AutoCloseable {

    void show(DesktopNotification notification);

    /** Releases any OS resource held (the tray icon). Idempotent. Never throws. */
    @Override
    void close();

    /** The fallback: accepts everything, shows nothing, holds nothing. */
    static DesktopNotifier disabled() {
        return new DesktopNotifier() {
            @Override
            public void show(DesktopNotification notification) {
                // no-op: this is the fallback for headless / tray-less / opted-out sessions
            }

            @Override
            public void close() {
                // no-op: nothing was ever opened
            }
        };
    }
}
