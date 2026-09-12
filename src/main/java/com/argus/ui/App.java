package com.argus.ui;

import com.argus.core.Vault;
import com.argus.core.VaultStore;
import java.lang.System.Logger.Level;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * The JavaFX lifecycle for Argus. {@link #start} loads and shows the login screen (plan
 * §7.15); on a successful unlock the scene root swaps to P0-02's placeholder. {@code start}
 * runs on the FX Application Thread and stays synchronous and trivial (invariant 3): the
 * blocking vault unlock happens inside {@link LoginController}'s background {@code Task}, not
 * here.
 */
public final class App extends Application {

    public static final String WINDOW_TITLE = "Argus";

    private static final System.Logger LOGGER = System.getLogger(App.class.getName());

    /** FX-thread-confined: assigned once in the login callback, read only by {@link #stop()}. */
    private Vault vault;

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
            scene.setRoot(buildPlaceholder());
        });

        stage.setTitle(WINDOW_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    /** P0-02's original placeholder, unchanged apart from living in its own method now. */
    private Parent buildPlaceholder() {
        Label bootLine = new Label("argus — no target loaded");
        bootLine.getStyleClass().add("boot-line");

        StackPane root = new StackPane(bootLine);
        root.getStyleClass().add("app-root");

        AnimationUtils.fadeInUp(bootLine);
        return root;
    }

    /**
     * JavaFX shutdown hook. Closes the vault, if one was ever unlocked — the zeroization-at
     * -app-close tie-in (plan §4.6). This is P0-02's designated home for invariant 6's executor
     * shutdown once P1-06 owns a scan pool; the vault has no pool and needs none.
     */
    @Override
    public void stop() {
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
