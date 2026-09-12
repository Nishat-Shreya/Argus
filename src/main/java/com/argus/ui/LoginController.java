package com.argus.ui;

import com.argus.core.OperatorId;
import com.argus.core.Vault;
import com.argus.core.VaultException;
import com.argus.core.VaultFormatException;
import com.argus.core.VaultNotFoundException;
import com.argus.core.VaultStore;
import com.argus.core.WrongMasterPasswordException;
import java.util.Arrays;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Login screen. Field wiring, validation dispatch and message display -- no crypto, no file
 * paths, no policy (plan §7.14). FXML requires a no-arg constructor, so dependencies arrive by
 * setter.
 */
public final class LoginController {

    private static final Duration STAGGER_STEP = Duration.millis(40);

    @FXML
    private VBox card;
    @FXML
    private TextField operatorIdField;
    @FXML
    private PasswordField masterPasswordField;
    @FXML
    private Button unlockButton;
    @FXML
    private Label messageLabel;

    private VaultStore store;
    private Consumer<Vault> onUnlocked;
    private boolean pendingCreate;

    /** Injected by App before the scene is shown. */
    public void setVaultStore(VaultStore store) {
        this.store = store;
    }

    /** Called on the FX thread with the unlocked vault. App swaps the scene root. */
    public void setOnUnlocked(Consumer<Vault> callback) {
        this.onUnlocked = callback;
    }

    @FXML
    private void initialize() {
        int index = 0;
        for (var child : card.getChildren()) {
            AnimationUtils.fadeInUp(child, STAGGER_STEP.multiply(index));
            index++;
        }
        operatorIdField.textProperty().addListener((obs, oldValue, newValue) -> pendingCreate = false);
    }

    @FXML
    private void onUnlock() {
        String operatorIdText = operatorIdField.getText();
        String passwordText = masterPasswordField.getText();
        int passwordLength = passwordText == null ? 0 : passwordText.length();

        LoginValidation.Result result =
                LoginValidation.check(operatorIdText, passwordLength, pendingCreate);
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(card);
            return;
        }

        OperatorId operator = result.operatorId();
        boolean creating = pendingCreate;
        char[] password = passwordText.toCharArray();

        unlockButton.setDisable(true);
        clearMessage();

        Task<Vault> task = new Task<>() {
            @Override
            protected Vault call() throws VaultException {
                try {
                    return creating ? store.create(operator, password) : store.open(operator, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        };

        task.setOnSucceeded(event -> {
            unlockButton.setDisable(false);
            masterPasswordField.clear();
            onUnlocked.accept(task.getValue());
        });

        task.setOnFailed(event -> {
            unlockButton.setDisable(false);
            handleFailure(operator, task.getException());
        });

        Thread thread = new Thread(task, "argus-vault-unlock");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleFailure(OperatorId operator, Throwable exception) {
        if (exception instanceof VaultNotFoundException) {
            pendingCreate = true;
            showMessage("no vault for operator '" + operator
                    + "' - press unlock again to create one", false);
        } else if (exception instanceof WrongMasterPasswordException) {
            showMessage(
                    "operator id or master password is wrong, or the vault file was modified",
                    true);
            AnimationUtils.shake(card);
            masterPasswordField.clear();
        } else if (exception instanceof VaultFormatException) {
            showMessage("the vault file for '" + operator + "' is unreadable", true);
            AnimationUtils.shake(card);
        } else if (exception instanceof VaultException || exception instanceof IllegalArgumentException) {
            showMessage(exception.getMessage(), true);
            AnimationUtils.shake(card);
        } else {
            showMessage("could not open the vault", true);
            AnimationUtils.shake(card);
        }
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
