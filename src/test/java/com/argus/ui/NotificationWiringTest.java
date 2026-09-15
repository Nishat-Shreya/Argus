package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Section 6.5 (W1-W3): reflective + source-scan guards on the modified {@code
 * DashboardController} and {@code App}.
 */
class NotificationWiringTest {

    @Test
    void w1DashboardControllerDeclaresNonVolatileNotifierAndHistoryFields() {
        Field notifierField = findFieldOfType(DashboardController.class, DesktopNotifier.class);
        assertTrue(notifierField != null,
                "DashboardController must declare a DesktopNotifier field");
        assertFalse(Modifier.isStatic(notifierField.getModifiers()));
        assertFalse(Modifier.isVolatile(notifierField.getModifiers()));

        Field historyField = findFieldOfType(DashboardController.class,
                com.argus.core.ScanHistory.class);
        assertTrue(historyField != null,
                "DashboardController must declare a ScanHistory field");
        assertFalse(Modifier.isStatic(historyField.getModifiers()));
        assertFalse(Modifier.isVolatile(historyField.getModifiers()));
    }

    @Test
    void w2AppClosesTheDesktopNotifierBetweenShutdownAndVaultClose() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        int shutdownIndex = source.indexOf("dashboardController.shutdown()");
        int notifierCloseIndex = source.indexOf("desktopNotifier.close()");
        int vaultCloseIndex = source.indexOf("vault.close()");

        assertTrue(shutdownIndex >= 0, "App.stop() must call dashboardController.shutdown()");
        assertTrue(notifierCloseIndex >= 0, "App.stop() must call desktopNotifier.close()");
        assertTrue(vaultCloseIndex >= 0, "App.stop() must call vault.close()");
        assertTrue(shutdownIndex < notifierCloseIndex,
                "desktopNotifier.close() must come after dashboardController.shutdown()");
        assertTrue(notifierCloseIndex < vaultCloseIndex,
                "desktopNotifier.close() must come before vault.close()");
    }

    @Test
    void w3DashboardControllerUsesANamedDaemonHistoryThread() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        assertTrue(source.contains("argus-scan-history"),
                "DashboardController must name its history-read thread argus-scan-history");
        assertTrue(source.contains("setDaemon(true)"),
                "DashboardController's history-read thread must be a daemon");
    }

    @Test
    void w4SystemTrayNotifierGuardsShowAgainstReinstallAfterClose() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/SystemTrayNotifier.java"));
        int closeIndex = source.indexOf("void close(");
        int closeSetTrueIndex = source.indexOf("closed = true;");
        int closeTrayNullCheckIndex = source.indexOf("trayIcon == null", closeIndex);
        int showIndex = source.indexOf("void show(");
        int showGuardIndex = source.indexOf("closed", showIndex);
        int installIndex = source.indexOf("install(", showIndex);

        assertTrue(closeSetTrueIndex >= 0, "close() must set a closed flag to true");
        assertTrue(closeTrayNullCheckIndex >= 0, "close() must still guard on trayIcon == null");
        assertTrue(closeSetTrueIndex < closeTrayNullCheckIndex,
                "closed = true must be set unconditionally, before the trayIcon == null "
                        + "early-return, so close() marks the notifier closed even when no "
                        + "tray icon was ever installed");
        assertTrue(showGuardIndex >= 0, "show()'s guard clause must check the closed flag");
        assertTrue(installIndex >= 0, "show() must still be able to install a tray icon");
        assertTrue(showGuardIndex < installIndex,
                "show() must check closed before reaching install(), so a Task callback "
                        + "landing on the FX thread after App.stop() -> desktopNotifier.close() "
                        + "can never reinstall a tray icon and re-trigger JDK-6412791");
    }

    private static Field findFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
