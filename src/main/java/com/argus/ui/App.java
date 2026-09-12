package com.argus.ui;

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

    /** FX-thread-confined: retained so returning from the key vault is a RESTORE, not a reload. */
    private Parent dashboardRoot;

    /** FX-thread-confined: assigned on first visit to the key-vault panel. */
    private KeyVaultController keyVaultController;

    /** FX-thread-confined: lazily loaded on first open, then retained. */
    private Parent keyVaultRoot;

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
     * JavaFX shutdown hook. Shuts the dashboard's scan down first (invariant 6 — P0-02's
     * reservation of this method for the scan {@code ExecutorService}), then closes the vault,
     * if one was ever unlocked — the zeroization-at-app-close tie-in (plan §4.6). Scan first,
     * vault second: the vault is not used by the scan, but "stop the work, then release the
     * credential" is the order that stays correct if that ever changes.
     */
    @Override
    public void stop() {
        if (dashboardController != null) {
            dashboardController.shutdown();
        }
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
