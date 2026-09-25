package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Section 6.5 / 6.11 (W1-W7): reflective + source-scan guards on the modified {@code
 * DashboardController} and {@code App}.
 */
class NotificationWiringTest {

    @Test
    void w1DashboardControllerDeclaresNonVolatileAlertChannelAndHistoryFields() {
        Field channelField = findFieldOfType(DashboardController.class, AlertChannel.class);
        assertTrue(channelField != null,
                "DashboardController must declare an AlertChannel field");
        assertFalse(Modifier.isStatic(channelField.getModifiers()));
        assertFalse(Modifier.isVolatile(channelField.getModifiers()));

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

    @Test
    void w5AppClosesAlertChannelsBeforeVaultCloseAndAfterDashboardShutdown() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        int shutdownIndex = source.indexOf("dashboardController.shutdown()");
        int alertChannelsCloseIndex = source.indexOf("alertChannels.close()");
        int vaultCloseIndex = source.lastIndexOf("vault.close()");

        assertTrue(shutdownIndex >= 0, "App.stop() must call dashboardController.shutdown()");
        assertTrue(alertChannelsCloseIndex >= 0, "App.stop() must call alertChannels.close()");
        assertTrue(vaultCloseIndex >= 0, "App.stop() must call vault.close()");
        assertTrue(shutdownIndex < alertChannelsCloseIndex,
                "alertChannels.close() must come after dashboardController.shutdown()");
        assertTrue(alertChannelsCloseIndex < vaultCloseIndex,
                "alertChannels.close() must come before vault.close() -- a queued webhook "
                        + "delivery may resolve its endpoint from the vault");
    }

    @Test
    void w6DashboardControllerDeclaresNoVaultFieldOrImport() throws IOException {
        for (Field field : DashboardController.class.getDeclaredFields()) {
            assertFalse(field.getType() == com.argus.core.Vault.class,
                    "DashboardController must declare no field of type Vault");
        }
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        assertFalse(source.contains("import com.argus.core.Vault;"),
                "DashboardController must not import com.argus.core.Vault");
    }

    @Test
    void w7WebhookAlertChannelHasTheCanonicalShutdownSequence() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/WebhookAlertChannel.java"));
        assertTrue(source.contains("shutdown()"));
        assertTrue(source.contains("awaitTermination"));
        assertTrue(source.contains("shutdownNow()"));
        assertTrue(source.contains("setDaemon(true)"));
    }

    @Test
    void w8OnlyWebhookSettingsContainsTheVaultEntryLiteral() throws IOException {
        Path uiSourceDir = Path.of("src/main/java/com/argus/ui");
        List<Path> offenders = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(uiSourceDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("WebhookSettings.java"))
                    .forEach(p -> {
                        try {
                            if (Files.readString(p).contains("notify.webhook.url")) {
                                offenders.add(p);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertEquals(List.of(), offenders,
                "only WebhookSettings.java may contain the literal vault entry name");
    }

    /**
     * Email goes out after EVERY successful scan, exactly once, and never for a failed one:
     * {@code maybeNotify} has a single call site, the eligibility gate (saved + COMPLETED) comes
     * before anything is sent, and the scan-completed notice has a single send point that is
     * reached from exactly two places -- the background task's succeeded and failed handlers,
     * which are mutually exclusive. The new-findings {@code deliver(alert)} path (webhook,
     * desktop) is still there, unchanged.
     */
    @Test
    void w7EmailIsSentOncePerSuccessfulScanAndTheAlertPathIsUnchanged() throws IOException {
        String source =
                readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));

        assertEquals(1, occurrences(source, "maybeNotify(outcome);"),
                "maybeNotify must be called exactly once per finished scan");
        assertEquals(1, occurrences(source, "channels.scanCompleted("),
                "the notice has one send point");
        assertEquals(2, occurrences(source, "sendCompletionNotice(outcome,"),
                "reached from the succeeded handler and the failed handler only");
        assertEquals(1, occurrences(source, "channels.deliver(alert);"),
                "the new-findings alert path (webhook/desktop) stays a single call");

        int gate = source.indexOf("ScanNotifications.eligible(outcome)");
        int firstNotice = source.indexOf("channels.scanCompleted(");
        assertTrue(gate >= 0 && gate < firstNotice,
                "the eligibility gate must run before any notice is sent");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0;
                index = haystack.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
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
