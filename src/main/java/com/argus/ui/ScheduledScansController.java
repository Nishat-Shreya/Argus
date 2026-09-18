package com.argus.ui;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScheduledScanArchive;
import java.time.ZoneId;
import java.util.List;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Scheduled/recurring-scans config panel (P3-08). Field wiring, validation dispatch, and
 * message display -- no scheduling policy, no persistence (the {@code KeyVaultController}
 * precedent): those live in {@link ScheduledScanValidation} and {@link ScheduledScanArchive}.
 *
 * Every write runs on a one-shot daemon {@code Task} named {@code argus-scheduled-scans}
 * (the {@code argus-vault-write} / {@code argus-tags} precedent) -- never on the FX thread.
 * There is no confirmation dialog on remove: deleting a schedule is a low-stakes, reversible
 * action (re-adding it is one form submission), the same call this project already made for
 * removing a tag (P3-07), unlike the higher-stakes note-delete confirmation (P3-06).
 */
public final class ScheduledScansController {

    @FXML
    private VBox card;
    @FXML
    private TableView<ScheduledScanRow> scheduleTable;
    @FXML
    private TextField targetField;
    @FXML
    private TextField intervalField;
    @FXML
    private Button addButton;
    @FXML
    private Button toggleEnabledButton;
    @FXML
    private Button removeButton;
    @FXML
    private Button backButton;
    @FXML
    private Label messageLabel;

    private ScheduledScanArchive archive;
    private Runnable onClose;

    /** FX-thread-confined; set once per {@link #refresh()} and passed explicitly to the pure
     *  formatter (the {@code FindingsDetailController} precedent). */
    private ZoneId zone = ZoneId.systemDefault();

    /** Single-flight guard; a write is in flight. FX-thread-confined, not volatile. */
    private boolean busy;

    /** Injected by App immediately after the FXML loads. */
    public void setArchive(ScheduledScanArchive injectedArchive) {
        this.archive = injectedArchive;
    }

    /** Injected by App: "put the dashboard root back". */
    public void setOnClose(Runnable handler) {
        this.onClose = handler;
    }

    /** Called by App on every entry to the panel. Must be called on the FX thread. */
    public void refresh() {
        zone = ZoneId.systemDefault();
        targetField.clear();
        intervalField.clear();
        clearMessage();
        scheduleTable.getSelectionModel().clearSelection();
        reload();
    }

    @FXML
    @SuppressWarnings("unchecked")
    private void initialize() {
        TableColumn<ScheduledScanRow, String> targetColumn =
                (TableColumn<ScheduledScanRow, String>) scheduleTable.getColumns().get(0);
        TableColumn<ScheduledScanRow, String> intervalColumn =
                (TableColumn<ScheduledScanRow, String>) scheduleTable.getColumns().get(1);
        TableColumn<ScheduledScanRow, String> enabledColumn =
                (TableColumn<ScheduledScanRow, String>) scheduleTable.getColumns().get(2);
        TableColumn<ScheduledScanRow, String> lastRunColumn =
                (TableColumn<ScheduledScanRow, String>) scheduleTable.getColumns().get(3);
        TableColumn<ScheduledScanRow, String> nextRunColumn =
                (TableColumn<ScheduledScanRow, String>) scheduleTable.getColumns().get(4);

        targetColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().target()));
        intervalColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().interval()));
        enabledColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().enabledText()));
        lastRunColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().lastRun()));
        nextRunColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().nextRun()));

        scheduleTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldRow, newRow) -> updateButtonsForSelection(newRow));
        updateButtonsForSelection(null);
    }

    @FXML
    private void onAdd() {
        if (busy) {
            return;
        }
        ScheduledScanValidation.TargetResult targetResult =
                ScheduledScanValidation.checkTarget(targetField.getText());
        if (!targetResult.valid()) {
            showMessage(targetResult.message());
            AnimationUtils.shake(card);
            return;
        }
        ScheduledScanValidation.IntervalResult intervalResult =
                ScheduledScanValidation.checkInterval(intervalField.getText());
        if (!intervalResult.valid()) {
            showMessage(intervalResult.message());
            AnimationUtils.shake(card);
            return;
        }

        String target = targetResult.target();
        int minutes = intervalResult.minutes();
        runWrite(() -> {
            archive.schedule(target, minutes);
        }, () -> {
            targetField.clear();
            intervalField.clear();
            showMessage("schedule added for " + target);
        });
    }

    @FXML
    private void onToggleEnabled() {
        if (busy) {
            return;
        }
        ScheduledScanRow selected = scheduleTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        boolean currentlyEnabled = "enabled".equals(selected.enabledText());
        runWrite(() -> archive.setEnabled(selected.id(), !currentlyEnabled),
                () -> showMessage(
                        (currentlyEnabled ? "disabled " : "enabled ") + selected.target()));
    }

    @FXML
    private void onRemove() {
        if (busy) {
            return;
        }
        ScheduledScanRow selected = scheduleTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        runWrite(() -> archive.delete(selected.id()),
                () -> showMessage("removed schedule for " + selected.target()));
    }

    @FXML
    private void onBack() {
        clearMessage();
        if (onClose != null) {
            onClose.run();
        }
    }

    /** One shape for every write: disable the form, run {@code action} on a daemon thread, then
     *  reload and show a message on success, or report the failure -- the same shape for add,
     *  toggle and remove so they cannot drift apart. */
    private void runWrite(WriteAction action, Runnable onSuccessMessage) {
        busy = true;
        clearMessage();
        setFormDisabled(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws ScanArchiveException {
                action.run();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            busy = false;
            onSuccessMessage.run();
            reload();
        });
        task.setOnFailed(event -> {
            busy = false;
            handleFailure(task.getException());
            setFormDisabled(false);
        });

        Thread thread = new Thread(task, "argus-scheduled-scans");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reloads the table from the archive on a daemon thread -- a read never blocks the FX
     *  thread (the {@code ScanHistory} / {@code TagArchive} precedent). */
    private void reload() {
        Task<List<ScheduledScanRow>> task = new Task<>() {
            @Override
            protected List<ScheduledScanRow> call() throws ScanArchiveException {
                return ScheduledScanRows.of(archive.list(), zone);
            }
        };
        task.setOnSucceeded(event -> {
            scheduleTable.setItems(FXCollections.observableArrayList(task.getValue()));
            setFormDisabled(false);
            updateButtonsForSelection(scheduleTable.getSelectionModel().getSelectedItem());
        });
        task.setOnFailed(event -> {
            handleFailure(task.getException());
            setFormDisabled(false);
        });

        Thread thread = new Thread(task, "argus-scheduled-scans");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateButtonsForSelection(ScheduledScanRow row) {
        toggleEnabledButton.setDisable(busy || row == null);
        removeButton.setDisable(busy || row == null);
    }

    private void setFormDisabled(boolean disabled) {
        addButton.setDisable(disabled);
        updateButtonsForSelection(scheduleTable.getSelectionModel().getSelectedItem());
    }

    private void handleFailure(Throwable exception) {
        if (exception instanceof ScanArchiveException) {
            showMessage("could not update the schedule database -- see log");
        } else {
            showMessage("could not update the schedule");
        }
        AnimationUtils.shake(card);
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void clearMessage() {
        messageLabel.setText(null);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }

    @FunctionalInterface
    private interface WriteAction {
        void run() throws ScanArchiveException;
    }
}
