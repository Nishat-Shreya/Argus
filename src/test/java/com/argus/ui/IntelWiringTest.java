package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source-scan guards on the P3-15 intel/KEV wiring -- the {@code NotificationWiringTest} /
 * {@code ScheduledScansWiringTest} precedent for shutdown-ordering and null-guard concerns a
 * plain unit test cannot reach (neither {@code DashboardController} nor {@code App} can be
 * constructed off the FXML/JavaFX toolkit in this suite).
 */
class IntelWiringTest {

    @Test
    void maybeEnrichIntelGuardsAgainstANullClientAnUnsavedScanAndACancelledScan()
            throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        int methodIndex = source.indexOf("private void maybeEnrichIntel(");
        int guardIndex = source.indexOf("intelClient == null", methodIndex);
        int savedGuardIndex = source.indexOf("!outcome.saved()", methodIndex);
        int cancelledGuardIndex = source.indexOf("TargetQueue.advancesAfter(", methodIndex);

        assertTrue(methodIndex >= 0, "DashboardController must declare maybeEnrichIntel(...)");
        assertTrue(guardIndex >= 0 && guardIndex < methodIndex + 300,
                "maybeEnrichIntel must guard against a null intelClient");
        assertTrue(savedGuardIndex >= 0 && savedGuardIndex < methodIndex + 300,
                "maybeEnrichIntel must guard against an unsaved scan");
        assertTrue(cancelledGuardIndex >= 0 && cancelledGuardIndex < methodIndex + 300,
                "maybeEnrichIntel must reuse TargetQueue.advancesAfter to skip a cancelled scan");
    }

    @Test
    void setIntelClientHonorsTheOptOutSystemProperty() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/DashboardController.java"));
        int methodIndex = source.indexOf("public void setIntelClient(");
        int optOutIndex = source.indexOf("\"argus.intel\"", methodIndex);

        assertTrue(methodIndex >= 0, "DashboardController must declare setIntelClient(...)");
        assertTrue(optOutIndex >= 0 && optOutIndex < methodIndex + 300,
                "setIntelClient must check the -Dargus.intel opt-out property");
    }

    @Test
    void appClosesTheIntelClientAfterAlertChannelsAndBeforeTheVault() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        int alertChannelsCloseIndex = source.indexOf("alertChannels.close()");
        int intelClientCloseIndex = source.indexOf("intelClient.close()");
        int vaultCloseIndex = source.lastIndexOf("vault.close()");

        assertTrue(alertChannelsCloseIndex >= 0, "App.stop() must call alertChannels.close()");
        assertTrue(intelClientCloseIndex >= 0, "App.stop() must call intelClient.close()");
        assertTrue(vaultCloseIndex >= 0, "App.stop() must call vault.close()");
        assertTrue(alertChannelsCloseIndex < intelClientCloseIndex,
                "intelClient.close() must come after alertChannels.close()");
        assertTrue(intelClientCloseIndex < vaultCloseIndex,
                "intelClient.close() must come before vault.close()");
    }

    @Test
    void appBuildsAllFourIntelSourcesFromTheUnlockedVault() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        for (String sourceClass : List.of(
                "new VirusTotalSource(unlockedVault)", "new ShodanSource(unlockedVault)",
                "new AbuseIpdbSource(unlockedVault)", "new CensysSource(unlockedVault)")) {
            assertTrue(source.contains(sourceClass),
                    "App must build " + sourceClass + " from the unlocked vault");
        }
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
