package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Charts screen. Field wiring, dispatch, and message display only (plan §4.3) -- every rule
 * worth getting wrong lives in {@link ChartData}, {@link ChartSlice}, {@link ChartBar} and
 * {@link ScanChoices}, all directly testable. Historical scope only: one selected persisted
 * scan, never the live dashboard (plan §0.1).
 */
public final class ChartsController {

    @FXML
    private VBox root;
    @FXML
    private DatePicker scanDatePicker;
    @FXML
    private ComboBox<ScanChoice> scanCombo;
    @FXML
    private Button showButton;
    @FXML
    private Button backButton;
    @FXML
    private Label messageLabel;
    @FXML
    private Label hiddenLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private HBox chartsBox;
    @FXML
    private PieChart portStatePie;
    @FXML
    private StackedBarChart<String, Number> portBarChart;
    @FXML
    private CategoryAxis portAxis;
    @FXML
    private NumberAxis countAxis;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /** {@code ScanChoices.selectable(loadedScans, ...)}, newest first. FX-thread-confined. */
    private List<ScanChoice> selectableChoices = List.of();

    /**
     * Single-flight guard; a background load or render is in flight. FX-thread-confined, not
     * volatile -- it is read-then-written (a compound op), and confinement to the FX thread is
     * what makes that safe (the {@code KeyVaultController.busy} / {@code ScanDiffController.busy}
     * precedent, plan §5).
     */
    private boolean busy;

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
        hiddenLabel.setText("");
        hideCharts();
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
    private void onShow() {
        if (busy) {
            return;
        }
        ScanChoice choice = scanCombo.getValue();
        if (choice == null) {
            showMessage("choose a completed scan first", true);
            AnimationUtils.shake(root);
            return;
        }

        long scanId = choice.scanId();
        busy = true;
        clearMessage();
        showButton.setDisable(true);

        Task<List<FindingSnapshot>> task = new Task<>() {
            @Override
            protected List<FindingSnapshot> call() throws ScanArchiveException {
                return history.listFindings(scanId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            showButton.setDisable(false);
            render(task.getValue());
        });

        task.setOnFailed(event -> {
            busy = false;
            showButton.setDisable(false);
            showMessage("could not load that scan's findings", true);
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
        ZoneId zone = ZoneId.systemDefault();
        this.selectableChoices = ScanChoices.selectable(scans, zone);
        int hidden = ScanChoices.hiddenCount(scans);
        hiddenLabel.setText(hidden == 0 ? ""
                : hidden + " scans hidden — cancelled or failed scans are partial and cannot be charted");

        if (selectableChoices.isEmpty()) {
            setControlsDisabled(true);
            scanDatePicker.setValue(null);
            scanCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least one completed scan is needed to chart", false);
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

    private void setControlsDisabled(boolean disabled) {
        showButton.setDisable(disabled);
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

    private void render(List<FindingSnapshot> findings) {
        summaryLabel.setText(ChartData.summaryLine(findings));

        if (!ChartData.hasProbeFindings(findings)) {
            hideCharts();
            showMessage("this scan recorded no port probes", false);
            return;
        }

        showCharts();
        renderPie(findings);
        renderBars(findings);
    }

    private void renderPie(List<FindingSnapshot> findings) {
        List<ChartSlice> slices = ChartData.portStateSlices(findings);
        ObservableList<PieChart.Data> data =
                FXCollections.observableArrayList();
        for (ChartSlice slice : slices) {
            data.add(new PieChart.Data(slice.label(), slice.count()));
        }
        portStatePie.setData(data);
        for (int i = 0; i < slices.size(); i++) {
            PieChart.Data datum = data.get(i);
            if (datum.getNode() != null) {
                datum.getNode().getStyleClass().add(slices.get(i).styleClass());
            }
        }
    }

    private void renderBars(List<FindingSnapshot> findings) {
        List<ChartBar> bars = ChartData.portBars(findings);

        // Clear order matters: clear stale data before setting new categories -- setting
        // categories while stale series still reference old ones is a known CategoryAxis
        // duplicate-category failure (plan §4.5.1).
        portBarChart.getData().clear();
        portAxis.getCategories().clear();
        portAxis.setCategories(
                FXCollections.observableArrayList(ChartData.portCategories(bars)));
        countAxis.setUpperBound(ChartData.barAxisUpperBound(bars));

        for (String state : ChartData.stateSeries(bars)) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(state);
            for (ChartBar bar : bars) {
                if (bar.state().equals(state)) {
                    XYChart.Data<String, Number> datum =
                            new XYChart.Data<>(bar.category(), bar.count());
                    series.getData().add(datum);
                }
            }
            portBarChart.getData().add(series);
            for (XYChart.Data<String, Number> datum : series.getData()) {
                if (datum.getNode() != null) {
                    datum.getNode().getStyleClass().add(ChartData.styleClassFor(state));
                }
            }
        }
    }

    private void showCharts() {
        chartsBox.setVisible(true);
        chartsBox.setManaged(true);
    }

    private void hideCharts() {
        portBarChart.getData().clear();
        portAxis.getCategories().clear();
        portStatePie.setData(FXCollections.observableArrayList());
        chartsBox.setVisible(false);
        chartsBox.setManaged(false);
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
