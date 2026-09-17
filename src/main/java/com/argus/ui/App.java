package com.argus.ui;

import com.argus.core.AnnotationArchive;
import com.argus.core.ScanHistory;
import com.argus.core.TagArchive;
import com.argus.core.Vault;
import com.argus.core.VaultStore;
import com.argus.core.WebhookSender;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.List;
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

    /** FX-thread-confined: created once, after unlock, and closed by {@link #stop()} before the
     *  vault (P3-03 §3.7) — the webhook worker inside it may resolve its endpoint from the
     *  vault, so the pool must be joined before the vault is closed. Defaults to the no-op
     *  fallback so a shutdown before unlock is safe. */
    private AlertChannel alertChannels = AlertChannels.none();

    /** FX-thread-confined: assigned on first visit to the notifications settings panel. */
    private NotificationSettingsController notificationSettingsController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent notificationSettingsRoot;

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

    /** FX-thread-confined: assigned on first visit to the report export panel. */
    private ReportController reportController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent reportRoot;

    /** FX-thread-confined: assigned on first visit to the findings detail panel. */
    private FindingsDetailController findingsDetailController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent findingsDetailRoot;

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
                dashboardController.setOpenReportHandler(() -> showReport(scene));
                dashboardController.setOpenNotificationSettingsHandler(
                        () -> showNotificationSettings(scene));
                dashboardController.setOpenFindingsDetailHandler(
                        () -> showFindingsDetail(scene));
                this.desktopNotifier = DesktopNotifiers.create();
                this.alertChannels = AlertChannels.of(List.of(
                        new DesktopAlertChannel(desktopNotifier),
                        new WebhookAlertChannel(WebhookSettings.fromVault(unlockedVault),
                                new WebhookSender())));
                dashboardController.setAlertChannel(alertChannels);
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
     * Loads {@code report-view.fxml} once, on first use, and retains it (the
     * {@link #showTimeline(Scene)} shape, plan §1's ninth root swap). Every subsequent open
     * reuses the same root and just calls {@link ReportController#refresh()}.
     */
    private void showReport(Scene scene) {
        if (reportRoot == null) {
            try {
                FXMLLoader reportLoader =
                        new FXMLLoader(getClass().getResource("report-view.fxml"));
                this.reportRoot = reportLoader.load();
                this.reportController = reportLoader.getController();
                reportController.setHistory(ScanHistory.atDefaultLocation());
                reportController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load report-view.fxml", e);
            }
        }
        reportController.refresh();
        scene.setRoot(reportRoot);
    }

    /**
     * Loads {@code notification-settings-view.fxml} once, on first use, and retains it (the
     * {@link #showTimeline(Scene)} shape, plan §3.7's eighth root swap). Every subsequent open
     * reuses the same root and just calls {@link NotificationSettingsController#refresh()}.
     */
    private void showNotificationSettings(Scene scene) {
        if (notificationSettingsRoot == null) {
            try {
                FXMLLoader notificationSettingsLoader = new FXMLLoader(
                        getClass().getResource("notification-settings-view.fxml"));
                this.notificationSettingsRoot = notificationSettingsLoader.load();
                this.notificationSettingsController = notificationSettingsLoader.getController();
                notificationSettingsController.setVault(vault);
                notificationSettingsController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to load notification-settings-view.fxml", e);
            }
        }
        notificationSettingsController.refresh();
        scene.setRoot(notificationSettingsRoot);
    }

    /**
     * Loads {@code findings-detail-view.fxml} once, on first use, and retains it (the
     * {@link #showReport(Scene)} shape, plan §1's tenth root swap). Every subsequent open reuses
     * the same root and just calls {@link FindingsDetailController#refresh()}.
     */
    private void showFindingsDetail(Scene scene) {
        if (findingsDetailRoot == null) {
            try {
                FXMLLoader findingsDetailLoader =
                        new FXMLLoader(getClass().getResource("findings-detail-view.fxml"));
                this.findingsDetailRoot = findingsDetailLoader.load();
                this.findingsDetailController = findingsDetailLoader.getController();
                findingsDetailController.setHistory(ScanHistory.atDefaultLocation());
                findingsDetailController.setNotes(AnnotationArchive.atDefaultLocation());
                findingsDetailController.setTags(TagArchive.atDefaultLocation());
                findingsDetailController.setOnClose(() -> scene.setRoot(dashboardRoot));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to load findings-detail-view.fxml", e);
            }
        }
        findingsDetailController.refresh();
        scene.setRoot(findingsDetailRoot);
    }

    /**
     * JavaFX shutdown hook. Shuts the dashboard's scan down first (invariant 6 — P0-02's
     * reservation of this method for the scan {@code ExecutorService}), then joins the webhook
     * worker pool (P3-03 §3.7 — a queued webhook delivery may resolve its endpoint from the
     * vault, so this pool must be gone before the vault is closed), then releases the
     * desktop-notifier's OS resource (P3-02 §3.7 — the tray icon must be gone before the FX
     * toolkit winds down, or AWT's non-daemon helper threads can keep the JVM alive, JDK-6412791;
     * this is {@code App.stop()}, not a shutdown hook, for the reason JDK-8042114 warns about),
     * then closes the vault, if one was ever unlocked — the zeroization-at-app-close tie-in
     * (plan §4.6). Scan first, alert channels second, notifier third, vault fourth: stop the
     * work, join anything that might still touch the vault, release the OS resource, then
     * release the credential.
     */
    @Override
    public void stop() {
        if (dashboardController != null) {
            dashboardController.shutdown();
        }
        alertChannels.close();
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
