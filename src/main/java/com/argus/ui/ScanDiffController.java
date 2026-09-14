package com.argus.ui;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScanComparison;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Scan diff screen. Field wiring, dispatch, and message display only (plan §3.6) — every rule
 * worth getting wrong lives in {@link ScanChoices}, {@link DiffRows}, {@link DiffValidation} and
 * {@link ScanHistory}, all directly testable.
 */
public final class ScanDiffController {

    @FXML
    private VBox root;
    @FXML
    private DatePicker baselineDatePicker;
    @FXML
    private ComboBox<ScanChoice> baselineScanCombo;
    @FXML
    private DatePicker currentDatePicker;
    @FXML
    private ComboBox<ScanChoice> currentScanCombo;
    @FXML
    private Button compareButton;
    @FXML
    private Button backButton;
    @FXML
    private Label summaryLabel;
    @FXML
    private Label messageLabel;
    @FXML
    private TableView<DiffRow> diffTable;
    @FXML
    private Label hiddenLabel;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /** The full, unfiltered load from the last {@link #refresh()}. FX-thread-confined. */
    private List<ScanSummary> loadedScans = List.of();

    /** {@code ScanChoices.selectable(loadedScans, ...)}, newest first. FX-thread-confined. */
    private List<ScanChoice> selectableChoices = List.of();

    /**
     * Single-flight guard; a background load or compare is in flight. FX-thread-confined, not
     * volatile — it is read-then-written (a compound op), and confinement to the FX thread is
     * what makes that safe (the {@code KeyVaultController.busy} precedent, plan §5).
     */
    private boolean busy;

    @FXML
    @SuppressWarnings("unchecked")
    private void initialize() {
        TableColumn<DiffRow, String> changeColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(0);
        TableColumn<DiffRow, String> typeColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(1);
        TableColumn<DiffRow, String> subjectColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(2);
        TableColumn<DiffRow, String> portColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(3);
        TableColumn<DiffRow, String> baselineColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(4);
        TableColumn<DiffRow, String> currentColumn =
                (TableColumn<DiffRow, String>) diffTable.getColumns().get(5);

        changeColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().change()));
        typeColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().type()));
        subjectColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().subject()));
        portColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().port()));
        baselineColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().baseline()));
        currentColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().current()));

        diffTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(DiffRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("diff-added", "diff-removed", "diff-changed");
                if (!empty && item != null) {
                    getStyleClass().add(item.styleClass());
                }
            }
        });

        StringConverter<ScanChoice> converter = new StringConverter<>() {
            @Override
            public String toString(ScanChoice choice) {
                return choice == null ? "" : choice.label();
            }

            @Override
            public ScanChoice fromString(String string) {
                return null;
            }
        };
        baselineScanCombo.setConverter(converter);
        currentScanCombo.setConverter(converter);

        baselineDatePicker.setDayCellFactory(picker -> dayCell());
        currentDatePicker.setDayCellFactory(picker -> dayCell());

        baselineDatePicker.valueProperty().addListener(
                (obs, oldDate, newDate) -> onDateChanged(baselineScanCombo, newDate));
        currentDatePicker.valueProperty().addListener(
                (obs, oldDate, newDate) -> onDateChanged(currentScanCombo, newDate));
    }

    /** Injected by App immediately after the FXML loads. */
    public void setHistory(ScanHistory injectedHistory) {
        this.history = injectedHistory;
    }

    /** Injected by App: "put the dashboard root back". */
    public void setOnClose(Runnable handler) {
        this.onClose = handler;
    }

    /** Called by App on every entry: starts a background load of the scan history. */
    public void refresh() {
        if (busy) {
            return;
        }
        clearMessage();
        summaryLabel.setText(null);
        diffTable.getItems().clear();
        hiddenLabel.setText("");
        setControlsDisabled(true);
        busy = true;

        Task<List<ScanSummary>> task = new Task<>() {
            @Override
            protected List<ScanSummary> call() throws ScanArchiveException {
                return history.listScans();
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            onScansLoaded(task.getValue());
        });

        task.setOnFailed(event -> {
            busy = false;
            showMessage("could not load the scan history", true);
        });

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onCompare() {
        if (busy) {
            return;
        }
        ScanSummary baselineSummary = selectedSummary(baselineScanCombo);
        ScanSummary currentSummary = selectedSummary(currentScanCombo);

        DiffValidation.Result result = DiffValidation.check(baselineSummary, currentSummary);
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(root);
            return;
        }

        long baselineId = baselineSummary.id();
        long currentId = currentSummary.id();
        busy = true;
        clearMessage();
        compareButton.setDisable(true);

        Task<ScanComparison> task = new Task<>() {
            @Override
            protected ScanComparison call() throws ScanArchiveException {
                return history.compare(baselineId, currentId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            compareButton.setDisable(false);
            ScanComparison comparison = task.getValue();
            summaryLabel.setText(DiffRows.summaryLine(comparison.diff()));
            diffTable.setItems(FXCollections.observableArrayList(DiffRows.of(comparison.diff())));
        });

        task.setOnFailed(event -> {
            busy = false;
            compareButton.setDisable(false);
            showMessage("could not compare those scans", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onBack() {
        clearMessage();
        if (onClose != null) {
            onClose.run();
        }
    }

    private void onScansLoaded(List<ScanSummary> scans) {
        this.loadedScans = scans;
        ZoneId zone = ZoneId.systemDefault();
        this.selectableChoices = ScanChoices.selectable(scans, zone);
        hiddenLabel.setText(ScanChoices.hiddenNote(scans));

        if (selectableChoices.size() < 2) {
            setControlsDisabled(true);
            baselineDatePicker.setValue(null);
            currentDatePicker.setValue(null);
            baselineScanCombo.setItems(FXCollections.observableArrayList());
            currentScanCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least two completed scans are needed to compare", false);
            return;
        }

        setControlsDisabled(false);
        ScanChoice newest = selectableChoices.get(0);
        ScanChoice nextNewest = selectableChoices.get(1);

        currentDatePicker.setValue(newest.date());
        baselineDatePicker.setValue(nextNewest.date());
        populateCombo(currentScanCombo, ScanChoices.onDate(selectableChoices, newest.date()),
                newest);
        populateCombo(baselineScanCombo, ScanChoices.onDate(selectableChoices, nextNewest.date()),
                nextNewest);
    }

    private void onDateChanged(ComboBox<ScanChoice> combo, LocalDate date) {
        if (date == null) {
            combo.setItems(FXCollections.observableArrayList());
            return;
        }
        List<ScanChoice> onDate = ScanChoices.onDate(selectableChoices, date);
        populateCombo(combo, onDate, onDate.isEmpty() ? null : onDate.get(0));
    }

    private void populateCombo(ComboBox<ScanChoice> combo, List<ScanChoice> choices,
            ScanChoice selection) {
        combo.setItems(FXCollections.observableArrayList(choices));
        combo.getSelectionModel().select(selection);
    }

    private ScanSummary selectedSummary(ComboBox<ScanChoice> combo) {
        ScanChoice choice = combo.getValue();
        if (choice == null) {
            return null;
        }
        for (ScanSummary scan : loadedScans) {
            if (scan.id() == choice.scanId()) {
                return scan;
            }
        }
        return null;
    }

    private void setControlsDisabled(boolean disabled) {
        compareButton.setDisable(disabled);
        baselineDatePicker.setDisable(disabled);
        currentDatePicker.setDisable(disabled);
        baselineScanCombo.setDisable(disabled);
        currentScanCombo.setDisable(disabled);
    }

    private DateCell dayCell() {
        return new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item == null
                        || !ScanChoices.dates(selectableChoices).contains(item));
            }
        };
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
