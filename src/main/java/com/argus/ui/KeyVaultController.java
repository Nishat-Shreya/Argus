package com.argus.ui;

import com.argus.core.Vault;
import com.argus.core.VaultException;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Key-vault settings panel. Field wiring, dispatch, and message display -- no crypto, no file
 * paths, no policy (the {@code LoginController} / {@code DashboardController} precedent).
 *
 * STRUCTURALLY unable to show a stored key back (invariant 7, pinned by
 * {@code NoKeyReadbackSourceTest}): this class declares no field of the blank-able text type,
 * never reads an entry value out of the vault, never repopulates the masked input, never logs,
 * and never forwards a caught exception's detail text -- every operator-facing message on a
 * path that touched key material is a fixed literal (plan §7.5).
 */
public final class KeyVaultController {

    @FXML
    private VBox card;
    @FXML
    private Label vaultLabel;
    @FXML
    private TableView<ApiKeyRow> sourcesTable;
    @FXML
    private Label editorLabel;
    @FXML
    private PasswordField keyField;
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

    /** Single-flight guard; a write is in flight. FX-thread-confined, not volatile (plan §4.4). */
    private boolean busy;

    /** First press of a two-press removal has happened. FX-thread-confined, not volatile. */
    private boolean pendingRemove;

    /** Injected by App immediately after the FXML loads. */
    public void setVault(Vault injectedVault) {
        this.vault = injectedVault;
        vaultLabel.setText("vault: " + injectedVault.operator().value());
    }

    /** Injected by App: "put the dashboard root back". */
    public void setOnClose(Runnable handler) {
        this.onClose = handler;
    }

    /**
     * Called by App on every entry to the panel: rebuild rows, clear the field and the
     * message, drop any half-armed removal. Must be called on the FX thread.
     */
    public void refresh() {
        pendingRemove = false;
        keyField.clear();
        clearMessage();
        sourcesTable.getSelectionModel().clearSelection();
        sourcesTable.setItems(FXCollections.observableArrayList(ApiKeyRows.from(vault.keys())));
        updateButtonsForSelection(null);
    }

    @FXML
    @SuppressWarnings("unchecked")
    private void initialize() {
        TableColumn<ApiKeyRow, String> sourceColumn =
                (TableColumn<ApiKeyRow, String>) sourcesTable.getColumns().get(0);
        TableColumn<ApiKeyRow, String> entryColumn =
                (TableColumn<ApiKeyRow, String>) sourcesTable.getColumns().get(1);
        TableColumn<ApiKeyRow, String> statusColumn =
                (TableColumn<ApiKeyRow, String>) sourcesTable.getColumns().get(2);

        sourceColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().displayName()));
        entryColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().entryName()));
        statusColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().statusText()));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("key-status-set", "key-status-missing");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    getStyleClass().add(
                            "configured".equals(item) ? "key-status-set" : "key-status-missing");
                }
            }
        });

        sourcesTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldRow, newRow) -> onSelectionChanged(newRow));
    }

    @FXML
    private void onSaveKey() {
        if (busy) {
            return;
        }
        ApiKeyRow selectedRow = sourcesTable.getSelectionModel().getSelectedItem();
        if (selectedRow == null) {
            showMessage("select a source first", true);
            AnimationUtils.shake(card);
            return;
        }

        ApiKeyValidation.Result result = ApiKeyValidation.check(keyField.getText());
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(card);
            return;
        }

        String normalizedKey = keyField.getText().strip();
        ApiKeySource source = selectedRow.source();
        pendingRemove = false;
        busy = true;
        clearMessage();
        saveButton.setDisable(true);
        removeButton.setDisable(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws VaultException {
                vault.put(source.entryName(), normalizedKey);
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            keyField.clear();
            showMessage("key saved for " + source.displayName(), false);
            reloadRowsPreservingSelection(source);
        });

        task.setOnFailed(event -> {
            busy = false;
            handleFailure(task.getException());
            updateButtonsForSelection(sourcesTable.getSelectionModel().getSelectedItem());
        });

        Thread thread = new Thread(task, "argus-vault-write");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onRemoveKey() {
        if (busy) {
            return;
        }
        ApiKeyRow selectedRow = sourcesTable.getSelectionModel().getSelectedItem();
        if (selectedRow == null || !selectedRow.configured()) {
            return;
        }

        if (!pendingRemove) {
            pendingRemove = true;
            showMessage("press remove again to delete the stored key for "
                    + selectedRow.displayName() + " -- it cannot be recovered", true);
            return;
        }

        pendingRemove = false;
        ApiKeySource source = selectedRow.source();
        busy = true;
        clearMessage();
        saveButton.setDisable(true);
        removeButton.setDisable(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws VaultException {
                return vault.remove(source.entryName());
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            showMessage("key removed for " + source.displayName(), false);
            reloadRowsPreservingSelection(source);
        });

        task.setOnFailed(event -> {
            busy = false;
            handleFailure(task.getException());
            updateButtonsForSelection(sourcesTable.getSelectionModel().getSelectedItem());
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

    private void onSelectionChanged(ApiKeyRow row) {
        pendingRemove = false;
        clearMessage();
        keyField.clear();
        if (row == null) {
            editorLabel.setText(null);
        } else {
            editorLabel.setText("key for " + row.displayName() + " · " + row.source().authHint());
        }
        updateButtonsForSelection(row);
    }

    private void updateButtonsForSelection(ApiKeyRow row) {
        if (row == null) {
            saveButton.setDisable(true);
            removeButton.setDisable(true);
        } else {
            saveButton.setDisable(busy);
            removeButton.setDisable(busy || !row.configured());
        }
    }

    /** Rebuilds the table rows from the vault and reselects the row for {@code source}. */
    private void reloadRowsPreservingSelection(ApiKeySource source) {
        sourcesTable.setItems(FXCollections.observableArrayList(ApiKeyRows.from(vault.keys())));
        for (ApiKeyRow row : sourcesTable.getItems()) {
            if (row.source() == source) {
                sourcesTable.getSelectionModel().select(row);
                break;
            }
        }
    }

    private void handleFailure(Throwable exception) {
        if (exception instanceof IllegalStateException) {
            showMessage("the vault is closed -- restart Argus and log in again", true);
        } else if (exception instanceof VaultException) {
            showMessage("could not write the vault file -- the key was not saved", true);
        } else if (exception instanceof IllegalArgumentException) {
            showMessage("that key was rejected by the vault", true);
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
