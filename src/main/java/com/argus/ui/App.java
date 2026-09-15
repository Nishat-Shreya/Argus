package com.argus.ui;

import com.argus.core.ScanHistory;
import com.argus.core.Vault;
import com.argus.core.VaultStore;
import java.io.IOException;
import java.lang.System.Logger.Level;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * The JavaFX lifecycle for Argus. {@link #start} loads and shows the login screen (plan
 * §7.15); on a successful unlock the scene root swaps to the dashboard (P1-06). {@code start}
 * runs on the FX Application Thread and stays synchronous and trivial (invariant 3): the
 * blocking vault unlock happens inside {@link LoginController}'s background {@code Task}, not
 * here.
 */
public final class App extends Application {

    public static final String WINDOW_TITLE = "Argus";

    private static final System.Logger LOGGER = System.getLogger(App.class.getName());

    /** FX-thread-confined: assigned once in the login callback, read only by {@link #stop()}. */
    private Vault vault;

    /** FX-thread-confined: assigned once on unlock, closed by {@link #stop()} (invariant 6). */
    private DashboardController dashboardController;

    /** FX-thread-confined: created once, after unlock, and closed by {@link #stop()} before the
     *  vault (P3-02 §3.7). Defaults to the no-op fallback so a shutdown before unlock is safe. */
    private DesktopNotifier desktopNotifier = DesktopNotifier.disabled();

    /** FX-thread-confined: retained so returning from the key vault is a RESTORE, not a reload. */
    private Parent dashboardRoot;

    /** FX-thread-confined: assigned on first visit to the key-vault panel. */
    private KeyVaultController keyVaultController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent keyVaultRoot;

    /** FX-thread-confined: assigned on first visit to the scan diff panel. */
    private ScanDiffController scanDiffController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent scanDiffRoot;

    /** FX-thread-confined: assigned on first visit to the charts panel. */
    private ChartsController chartsController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent chartsRoot;

    /** FX-thread-confined: assigned on first visit to the network graph panel. */
    private GraphController graphController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent graphRoot;

    /** FX-thread-confined: assigned on first visit to the timeline panel. */
    private TimelineController timelineController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent timelineRoot;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("login-view.fxml"));
        Parent loginRoot = loader.load();
        LoginController controller = loader.getController();
        controller.setVaultStore(VaultStore.atDefaultLocation());

        Scene scene = new Scene(loginRoot, 1280, 800);
        Theme.applyTo(scene);

        controller.setOnUnlocked(unlockedVault -> {
            this.vault = unlockedVault;
            try {
                FXMLLoader dashboardLoader =
                        new FXMLLoader(getClass().getResource("dashboard-view.fxml"));
                this.dashboardRoot = dashboardLoader.load();
                this.dashboardController = dashboardLoader.getController();
                dashboardController.setOpenKeySettingsHandler(() -> showKeyVault(scene));
                dashboardController.setOpenDiffHandler(() -> showScanDiff(scene));
                dashboardController.setOpenChartsHandler(() -> showCharts(scene));
                dashboardController.setOpenGraphHandler(() -> showGraph(scene));
                dashboardController.setOpenTimelineHandler(() -> showTimeline(scene));
                this.desktopNotifier = DesktopNotifiers.create();
                dashboardController.setDesktopNotifier(desktopNotifier);
                scene.setRoot(dashboardRoot);
            } catch (IOException e) {
                throw new IllegalStateException("failed to load dashboard-view.fxml", e);
            }
        });

        stage.setTitle(WINDOW_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Loads {@code key-vault-view.fxml} once, on first use, and retains it (plan §7.1's third
     * screen via {@code scene.setRoot} swap — no modal Stage, no router). Every subsequent
     * open reuses the same root and just calls {@link KeyVaultController#refresh()}.
     */
    private void showKeyVault(Scene scene) {
        if (keyVaultRoot == null) {
            try {
                FXMLLoader keyVaultLoader =
                        new FXMLLoader(getClass().getResource("key-vault-view.fxml"));
                this.keyVaultRoot = keyVaultLoader.load();
                this.keyVaultController = keyVaultLoader.getController();
                keyVaultController.setVault(vault);
                keyVaultController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load key-vault-view.fxml", e);
            }
        }
        keyVaultController.refresh();
        scene.setRoot(keyVaultRoot);
    }

    /**
     * Loads {@code scan-diff-view.fxml} once, on first use, and retains it (the
     * {@link #showKeyVault(Scene)} shape, plan §4.5's fourth root swap). Every subsequent open
     * reuses the same root and just calls {@link ScanDiffController#refresh()}.
     */
    private void showScanDiff(Scene scene) {
        if (scanDiffRoot == null) {
            try {
                FXMLLoader scanDiffLoader =
                        new FXMLLoader(getClass().getResource("scan-diff-view.fxml"));
                this.scanDiffRoot = scanDiffLoader.load();
                this.scanDiffController = scanDiffLoader.getController();
                scanDiffController.setHistory(ScanHistory.atDefaultLocation());
                scanDiffController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load scan-diff-view.fxml", e);
            }
        }
        scanDiffController.refresh();
        scene.setRoot(scanDiffRoot);
    }

    /**
     * Loads {@code charts-view.fxml} once, on first use, and retains it (the
     * {@link #showScanDiff(Scene)} shape, plan §1's fifth root swap). Every subsequent open
     * reuses the same root and just calls {@link ChartsController#refresh()}.
     */
    private void showCharts(Scene scene) {
        if (chartsRoot == null) {
            try {
                FXMLLoader chartsLoader =
                        new FXMLLoader(getClass().getResource("charts-view.fxml"));
                this.chartsRoot = chartsLoader.load();
                this.chartsController = chartsLoader.getController();
                chartsController.setHistory(ScanHistory.atDefaultLocation());
                chartsController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load charts-view.fxml", e);
            }
        }
        chartsController.refresh();
        scene.setRoot(chartsRoot);
    }

    /**
     * Loads {@code graph-view.fxml} once, on first use, and retains it (the
     * {@link #showCharts(Scene)} shape, plan §1's sixth root swap). Every subsequent open reuses
     * the same root and just calls {@link GraphController#refresh()}.
     */
    private void showGraph(Scene scene) {
        if (graphRoot == null) {
            try {
                FXMLLoader graphLoader =
                        new FXMLLoader(getClass().getResource("graph-view.fxml"));
                this.graphRoot = graphLoader.load();
                this.graphController = graphLoader.getController();
                graphController.setHistory(ScanHistory.atDefaultLocation());
                graphController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load graph-view.fxml", e);
            }
        }
        graphController.refresh();
        scene.setRoot(graphRoot);
    }

    /**
     * Loads {@code timeline-view.fxml} once, on first use, and retains it (the
     * {@link #showGraph(Scene)} shape, plan §1's seventh root swap). Every subsequent open
     * reuses the same root and just calls {@link TimelineController#refresh()}.
     */
    private void showTimeline(Scene scene) {
        if (timelineRoot == null) {
            try {
                FXMLLoader timelineLoader =
                        new FXMLLoader(getClass().getResource("timeline-view.fxml"));
                this.timelineRoot = timelineLoader.load();
                this.timelineController = timelineLoader.getController();
                timelineController.setHistory(ScanHistory.atDefaultLocation());
                timelineController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load timeline-view.fxml", e);
            }
        }
        timelineController.refresh();
        scene.setRoot(timelineRoot);
    }

    /**
     * JavaFX shutdown hook. Shuts the dashboard's scan down first (invariant 6 — P0-02's
     * reservation of this method for the scan {@code ExecutorService}), then releases the
     * desktop-notifier's OS resource (P3-02 §3.7 — the tray icon must be gone before the FX
     * toolkit winds down, or AWT's non-daemon helper threads can keep the JVM alive, JDK-6412791;
     * this is {@code App.stop()}, not a shutdown hook, for the reason JDK-8042114 warns about),
     * then closes the vault, if one was ever unlocked — the zeroization-at-app-close tie-in
     * (plan §4.6). Scan first, notifier second, vault third: stop the work, release the OS
     * resource, then release the credential.
     */
    @Override
    public void stop() {
        if (dashboardController != null) {
            dashboardController.shutdown();
        }
        desktopNotifier.close();
        if (vault != null) {
            try {
                vault.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "failed to close vault for operator '" + vault.operator() + "'", e);
            }
        }
    }
}
