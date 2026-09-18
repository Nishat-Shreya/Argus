package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Report export screen. Field wiring, two {@code Task} dispatches and one {@code FileChooser}
 * call only (plan §3.5) -- every rule about what the report contains and what it is called
 * lives in {@link Reports}, {@link HtmlReport} and {@link ReportFiles}. Historical scope only:
 * one selected {@code COMPLETED} persisted scan's findings, never the live dashboard and never a
 * diff (plan §0.1).
 */
public final class ReportController {

    @FXML
    private VBox root;
    @FXML
    private DatePicker scanDatePicker;
    @FXML
    private ComboBox<ScanChoice> scanCombo;
    @FXML
    private Button previewButton;
    @FXML
    private Button exportButton;
    @FXML
    private Button exportPdfButton;
    @FXML
    private Button backButton;
    @FXML
    private Label targetLabel;
    @FXML
    private Label timestampLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private TableView<FindingRow> findingsTable;
    @FXML
    private Label hiddenLabel;
    @FXML
    private Label messageLabel;
    @FXML
    private Label previewNoteLabel;
    @FXML
    private Label exportHintLabel;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /**
     * Single-flight guard; a background load, preview or export is in flight. FX-thread-
     * confined, not volatile -- it is read-then-written (a compound op), and confinement to the
     * FX thread is what makes that safe (the {@code ChartsController.busy} /
     * {@code GraphController.busy} precedent, plan §5).
     */
    private boolean busy;

    /** The full, unfiltered load from the last {@link #refresh()}. FX-thread-confined. Retained
     *  so the {@code ScanSummary} for the selected {@code ScanChoice} can be looked up without a
     *  new {@code core} query (the {@code GraphController} precedent). */
    private List<ScanSummary> loadedScans = List.of();

    /** {@code ScanChoices.selectable(loadedScans, ...)}, newest first. FX-thread-confined. */
    private List<ScanChoice> selectableChoices = List.of();

    /** {@code ZoneId.systemDefault()}, read once per {@link #refresh()} and passed explicitly
     *  into {@link Reports} (plan §5.4) -- never read by {@code Reports} itself. */
    private ZoneId zone;

    /** The last successful preview, or {@code null} before one lands. FX-thread-confined. Export
     *  cannot be reached before a successful preview: {@code exportButton} starts disabled and
     *  is only enabled once this is non-null (plan §5.5). */
    private ReportModel model;

    @FXML
    private void initialize() {
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
        scanCombo.setConverter(converter);

        scanDatePicker.setDayCellFactory(picker -> dayCell());
        scanDatePicker.valueProperty().addListener(
                (obs, oldDate, newDate) -> onDateChanged(newDate));

        @SuppressWarnings("unchecked")
        TableColumn<FindingRow, String> typeColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(0);
        @SuppressWarnings("unchecked")
        TableColumn<FindingRow, String> subjectColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(1);
        @SuppressWarnings("unchecked")
        TableColumn<FindingRow, String> portColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(2);
        @SuppressWarnings("unchecked")
        TableColumn<FindingRow, String> stateColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(3);
        typeColumn.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(
                cd.getValue().type()));
        subjectColumn.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(
                cd.getValue().subject()));
        portColumn.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(
                cd.getValue().port()));
        stateColumn.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(
                cd.getValue().state()));
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
        hiddenLabel.setText("");
        clearPreview();
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
    private void onPreview() {
        if (busy) {
            return;
        }
        ScanChoice choice = scanCombo.getValue();
        ScanSummary summary = choice == null ? null : summaryFor(choice.scanId());
        if (choice == null || summary == null) {
            showMessage("choose a completed scan first", true);
            AnimationUtils.shake(root);
            return;
        }

        long scanId = choice.scanId();
        busy = true;
        clearMessage();
        previewButton.setDisable(true);
        exportButton.setDisable(true);
        exportPdfButton.setDisable(true);

        Task<List<FindingSnapshot>> task = new Task<>() {
            @Override
            protected List<FindingSnapshot> call() throws ScanArchiveException {
                return history.listFindings(scanId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            previewButton.setDisable(false);
            List<FindingSnapshot> findings = task.getValue();
            renderPreview(Reports.model(summary, findings, zone));
            if (findings.isEmpty()) {
                showMessage("this scan recorded no findings", false);
            } else {
                clearMessage();
            }
        });

        task.setOnFailed(event -> {
            busy = false;
            previewButton.setDisable(false);
            showMessage("could not load that scan's findings", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onExport() {
        if (busy || model == null) {
            return;
        }
        ReportModel currentModel = model;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("export report");
        chooser.setInitialFileName(Reports.suggestedFileName(currentModel));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("HTML document", "*.html"));

        Window window = root.getScene() == null ? null : root.getScene().getWindow();
        File chosen = chooser.showSaveDialog(window);
        if (chosen == null) {
            // Cancelling the dialog is a silent no-op, not an error (plan §5.5).
            return;
        }

        String html = HtmlReport.render(currentModel);
        java.nio.file.Path path = chosen.toPath();

        beginExport();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws java.io.IOException {
                ReportFiles.write(path, html);
                return null;
            }
        };
        task.setOnSucceeded(event -> endExport("exported to " + path));
        task.setOnFailed(event -> failExport());

        Thread thread = new Thread(task, "argus-report-export");
        thread.setDaemon(true);
        thread.start();
    }

    /** The {@link #onExport} shape, reusing {@link PdfReport} instead of {@link HtmlReport} --
     *  same {@link ReportModel}, same {@code argus-report-export} thread, same busy/message
     *  handling (P3-18). */
    @FXML
    private void onExportPdf() {
        if (busy || model == null) {
            return;
        }
        ReportModel currentModel = model;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("export report");
        chooser.setInitialFileName(
                Reports.suggestedFileName(currentModel).replace(".html", ".pdf"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF document", "*.pdf"));

        Window window = root.getScene() == null ? null : root.getScene().getWindow();
        File chosen = chooser.showSaveDialog(window);
        if (chosen == null) {
            return;
        }

        byte[] pdf = PdfReport.render(currentModel);
        java.nio.file.Path path = chosen.toPath();

        beginExport();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws java.io.IOException {
                ReportFiles.write(path, pdf);
                return null;
            }
        };
        task.setOnSucceeded(event -> endExport("exported to " + path));
        task.setOnFailed(event -> failExport());

        Thread thread = new Thread(task, "argus-report-export");
        thread.setDaemon(true);
        thread.start();
    }

    private void beginExport() {
        busy = true;
        clearMessage();
        exportButton.setDisable(true);
        exportPdfButton.setDisable(true);
    }

    private void endExport(String message) {
        busy = false;
        exportButton.setDisable(false);
        exportPdfButton.setDisable(false);
        showMessage(message, false);
    }

    private void failExport() {
        busy = false;
        exportButton.setDisable(false);
        exportPdfButton.setDisable(false);
        showMessage("could not write the report to that location", true);
        AnimationUtils.shake(root);
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
        this.zone = ZoneId.systemDefault();
        this.selectableChoices = ScanChoices.selectable(scans, zone);
        int hidden = ScanChoices.hiddenCount(scans);
        hiddenLabel.setText(Reports.hiddenNote(hidden));

        if (selectableChoices.isEmpty()) {
            setControlsDisabled(true);
            scanDatePicker.setValue(null);
            scanCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least one completed scan is needed to export a report", false);
            return;
        }

        setControlsDisabled(false);
        ScanChoice newest = selectableChoices.get(0);
        scanDatePicker.setValue(newest.date());
        populateCombo(ScanChoices.onDate(selectableChoices, newest.date()), newest);
    }

    private void onDateChanged(LocalDate date) {
        if (date == null) {
            scanCombo.setItems(FXCollections.observableArrayList());
            return;
        }
        List<ScanChoice> onDate = ScanChoices.onDate(selectableChoices, date);
        populateCombo(onDate, onDate.isEmpty() ? null : onDate.get(0));
    }

    private void populateCombo(List<ScanChoice> choices, ScanChoice selection) {
        scanCombo.setItems(FXCollections.observableArrayList(choices));
        scanCombo.getSelectionModel().select(selection);
    }

    private ScanSummary summaryFor(long scanId) {
        for (ScanSummary scan : loadedScans) {
            if (scan.id() == scanId) {
                return scan;
            }
        }
        return null;
    }

    private void setControlsDisabled(boolean disabled) {
        previewButton.setDisable(disabled);
        scanDatePicker.setDisable(disabled);
        scanCombo.setDisable(disabled);
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

    private void renderPreview(ReportModel builtModel) {
        this.model = builtModel;
        targetLabel.setText(builtModel.target());
        String finished = builtModel.finishedAt().isEmpty()
                ? "(not finished)"
                : builtModel.finishedAt();
        timestampLabel.setText("started " + builtModel.startedAt() + " · finished " + finished);
        summaryLabel.setText(builtModel.summaryLine());
        findingsTable.setItems(FXCollections.observableArrayList(builtModel.rows()));
        exportButton.setDisable(false);
        exportPdfButton.setDisable(false);
    }

    private void clearPreview() {
        model = null;
        targetLabel.setText(null);
        timestampLabel.setText(null);
        summaryLabel.setText(null);
        findingsTable.setItems(FXCollections.observableArrayList());
        exportButton.setDisable(true);
        exportPdfButton.setDisable(true);
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
