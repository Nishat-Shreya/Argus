package com.argus.ui;

import com.argus.core.Vault;
import com.argus.core.VaultException;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Notifications settings panel: where the operator configures the webhook URL (plan §3.5). The
 * 8th {@code scene.setRoot} screen, deliberately the smallest in the app.
 *
 * STRUCTURALLY unable to show a stored URL back (invariant 7, pinned by
 * {@code NoKeyReadbackSourceTest}): this class declares no field of the blank-able text type,
 * never reads the entry's value out of the vault, never repopulates the masked input, and never
 * forwards a caught exception's detail text — the {@code KeyVaultController} discipline, applied
 * here too.
 */
public final class NotificationSettingsController {

    @FXML
    private VBox card;
    @FXML
    private Label statusLabel;
    @FXML
    private PasswordField urlField;
    @FXML
    private Button saveButton;
    @FXML
    private Button removeButton;
    @FXML
    private Button backButton;
    @FXML
    private Label messageLabel;

    private Vault vault;
    private Runnable onClose;

    /** Single-flight guard; a write is in flight. FX-thread-confined, not volatile. */
    private boolean busy;

    /** First press of a two-press removal has happened. FX-thread-confined, not volatile. */
    private boolean pendingRemove;

    /** Injected by App immediately after the FXML loads. */
    public void setVault(Vault injectedVault) {
        this.vault = injectedVault;
    }

    /** Injected by App: "put the dashboard root back". */
    public void setOnClose(Runnable handler) {
        this.onClose = handler;
    }

    /**
     * Called by App on every entry to the panel: clear the field and the message, drop any
     * half-armed removal, and re-read the configured/not-configured status. Must be called on
     * the FX thread.
     */
    public void refresh() {
        pendingRemove = false;
        urlField.clear();
        clearMessage();
        updateStatusAndButtons();
    }

    @FXML
    private void onSave() {
        if (busy) {
            return;
        }
        WebhookSettings.Result result = WebhookSettings.check(urlField.getText());
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(card);
            return;
        }

        String normalizedUrl = urlField.getText().strip();
        pendingRemove = false;
        busy = true;
        clearMessage();
        saveButton.setDisable(true);
        removeButton.setDisable(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws VaultException {
                vault.put(WebhookSettings.VAULT_ENTRY, normalizedUrl);
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            urlField.clear();
            showMessage("saved", false);
            updateStatusAndButtons();
        });

        task.setOnFailed(event -> {
            busy = false;
            handleFailure(task.getException());
            updateStatusAndButtons();
        });

        Thread thread = new Thread(task, "argus-vault-write");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onRemove() {
        if (busy) {
            return;
        }
        if (!vault.keys().contains(WebhookSettings.VAULT_ENTRY)) {
            return;
        }

        if (!pendingRemove) {
            pendingRemove = true;
            showMessage(
                    "press remove again to delete the stored webhook URL -- it cannot be recovered",
                    true);
            return;
        }

        pendingRemove = false;
        busy = true;
        clearMessage();
        saveButton.setDisable(true);
        removeButton.setDisable(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws VaultException {
                return vault.remove(WebhookSettings.VAULT_ENTRY);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            showMessage("removed", false);
            updateStatusAndButtons();
        });

        task.setOnFailed(event -> {
            busy = false;
            handleFailure(task.getException());
            updateStatusAndButtons();
        });

        Thread thread = new Thread(task, "argus-vault-write");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onBack() {
        pendingRemove = false;
        clearMessage();
        if (onClose != null) {
            onClose.run();
        }
    }

    private void updateStatusAndButtons() {
        boolean configured = vault.keys().contains(WebhookSettings.VAULT_ENTRY);
        statusLabel.setText(configured ? "configured" : "not configured");
        saveButton.setDisable(busy);
        removeButton.setDisable(busy || !configured);
    }

    private void handleFailure(Throwable exception) {
        if (exception instanceof IllegalStateException) {
            showMessage("the vault is closed -- restart Argus and log in again", true);
        } else if (exception instanceof VaultException) {
            showMessage("could not write the vault file -- the URL was not saved", true);
        } else if (exception instanceof IllegalArgumentException) {
            showMessage("that URL was rejected by the vault", true);
        } else {
            showMessage("could not update the vault", true);
        }
        AnimationUtils.shake(card);
    }

    private void showMessage(String text, boolean isError) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("form-error", "form-hint");
        messageLabel.getStyleClass().add(isError ? "form-error" : "form-hint");
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void clearMessage() {
        messageLabel.setText(null);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }
}
