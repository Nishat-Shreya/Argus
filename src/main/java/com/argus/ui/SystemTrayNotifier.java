package com.argus.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.lang.System.Logger.Level;

/**
 * THE ONLY FILE IN {@code com.argus.ui} PERMITTED TO IMPORT {@code java.awt.*} (pinned by
 * {@code AwtIsolationTest}). Zero {@code javafx} imports.
 *
 * <p>THREAD CONFINEMENT: {@link #show} and {@link #close} are called ONLY from the FX
 * Application Thread ({@code DashboardController}'s {@code Task} callbacks and {@code
 * App.stop()}). The {@code trayIcon} field is therefore FX-thread-confined: it is NOT {@code
 * volatile}, needs no lock, and invariant 5 has no subject matter here (plan §3.4, §4.3).
 *
 * <p>No {@code EventQueue.invokeLater} wrapper. {@code SystemTray} and {@code TrayIcon} are
 * documented thread-safe, both call sites are already the FX thread, and posting to the AWT EDT
 * would move the icon's lifetime onto a thread {@code App.stop()} cannot wait for — reintroducing
 * exactly the exit hazard {@link #close()} exists to remove. Do not "fix" this without
 * re-planning (R6's sibling).
 */
final class SystemTrayNotifier implements DesktopNotifier {

    private static final System.Logger LOGGER =
            System.getLogger(SystemTrayNotifier.class.getName());

    private static final int ICON_SIZE = 16;
    private static final Color ACCENT_COLOR = new Color(0x3d, 0xdc, 0x5f); // theme.css accent

    private TrayIcon trayIcon;
    private boolean degraded;
    /**
     * Set once by {@link #close()}. Guards against the shutdown race where a background {@code
     * Task}'s {@code setOnSucceeded} callback (FX thread) calls {@link #show} after {@code
     * App.stop()} has already called {@link #close()} — without this flag, {@code show}'s
     * lazy-install branch would silently reinstall a live {@code TrayIcon} into the OS tray at the
     * exact moment the JVM is trying to exit, reintroducing the JDK-6412791 hazard {@link
     * #close()} exists to remove.
     */
    private boolean closed;

    /** {@code !isHeadless() && SystemTray.isSupported()} — never throws (plan §3.4). */
    static boolean isAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void show(DesktopNotification notification) {
        if (notification == null || closed || degraded) {
            return;
        }
        try {
            if (trayIcon == null) {
                trayIcon = install();
            }
            trayIcon.displayMessage(notification.caption(), notification.text(),
                    TrayIcon.MessageType.INFO);
        } catch (AWTException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "desktop notification failed; disabling for this session",
                    e);
            degraded = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (trayIcon == null) {
            return;
        }
        try {
            SystemTray.getSystemTray().remove(trayIcon);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to remove the tray icon", e);
        } finally {
            trayIcon = null;
        }
    }

    private static TrayIcon install() throws AWTException {
        Image image = buildIconImage();
        TrayIcon icon = new TrayIcon(image, "Argus");
        icon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(icon);
        return icon;
    }

    private static Image buildIconImage() {
        BufferedImage image =
                new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(ACCENT_COLOR);
            graphics.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
