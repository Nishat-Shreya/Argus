package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Timeline screen. Field wiring, two {@code Task} dispatches, and {@link #renderPosition}
 * only (plan §3.5, §4) -- every axis rule lives in {@link Timelines} and {@link TimelineTrack};
 * every row-mapping rule lives in {@link SnapshotRows}. "Scrub" means state-at-position, never a
 * diff (plan §0.1): no added/removed/changed marker is computed anywhere in this class.
 */
public final class TimelineController {

    private static final TimelineTrack EMPTY_TRACK = new TimelineTrack("", List.of(), 0);

    @FXML
    private VBox root;
    @FXML
    private ComboBox<String> targetCombo;
    @FXML
    private Button loadButton;
    @FXML
    private Button backButton;
    @FXML
    private Slider timelineSlider;
    @FXML
    private Label positionLabel;
    @FXML
    private Label targetLabel;
    @FXML
    private Label messageLabel;
    @FXML
    private Label hiddenLabel;
    @FXML
    private Label truncationLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private TableView<FindingRow> findingsTable;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /**
     * Single-flight guard; a background load or render is in flight. FX-thread-confined, not
     * volatile -- it is read-then-written (a compound op), and confinement to the FX thread is
     * what makes that safe (the {@code ChartsController.busy} / {@code GraphController.busy}
     * precedent, plan §5).
     */
    private boolean busy;

    /** The full, unfiltered load from the last {@link #refresh()}. FX-thread-confined. */
    private List<ScanSummary> loadedScans = List.of();

    /** {@code ZoneId.systemDefault()}, read once per {@link #refresh()} and passed explicitly
     *  into {@link Timelines} (plan §5) -- never read by {@code Timelines} itself. */
    private ZoneId zone;

    /** The currently loaded target's axis, or the empty track before any load.
     *  FX-thread-confined. */
    private TimelineTrack track = EMPTY_TRACK;

    /**
     * Every point on {@link #track}'s findings, prefetched by one background {@code Task}
     * before the slider is enabled (plan §0.3). Immutable ({@code Map.copyOf} of
     * {@code List.copyOf}s) -- scrubbing after this is a pure map lookup, zero I/O.
     * FX-thread-confined.
     */
    private Map<Long, List<FindingSnapshot>> findingsByScanId = Map.of();

    /** The slider index currently rendered, or {@code -1} when nothing is rendered yet.
     *  FX-thread-confined, not volatile -- read-then-written from the value listener, safe only
     *  because it is confined to the FX thread (plan §5). */
    private int renderedIndex = -1;

    /** The target selected on the last successful load, preserved across re-entry (plan §4.4,
     *  M13). FX-thread-confined. */
    private String selectedTarget;

    @FXML
    @SuppressWarnings("unchecked")
    private void initialize() {
        TableColumn<FindingRow, String> typeColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(0);
        TableColumn<FindingRow, String> subjectColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(1);
        TableColumn<FindingRow, String> portColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(2);
        TableColumn<FindingRow, String> stateColumn =
                (TableColumn<FindingRow, String>) findingsTable.getColumns().get(3);
        typeColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().type()));
        subjectColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().subject()));
        portColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().port()));
        stateColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().state()));

        // §4.2 trap 3: idempotent, never writes back to the slider's own value property.
        timelineSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int index = Timelines.indexFor(newValue.doubleValue(), track.size());
            if (index < 0 || index == renderedIndex) {
                return;
            }
            renderPosition(index);
        });
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
        truncationLabel.setText("");
        clearTimelineDisplay();
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
    private void onLoad() {
        if (busy) {
            return;
        }
        String target = targetCombo.getValue();
        if (target == null) {
            showMessage("choose a target first", true);
            AnimationUtils.shake(root);
            return;
        }
        startLoad(target);
    }

    @FXML
    private void onBack() {
        clearMessage();
        if (onClose != null) {
            onClose.run();
        }
    }

    private void onScansLoaded(List<ScanSummary> scans) {
        loadedScans = scans;
        zone = ZoneId.systemDefault();
        hiddenLabel.setText(Timelines.hiddenNote(ScanChoices.hiddenCount(scans)));

        List<String> targets = Timelines.targets(scans);
        if (targets.isEmpty()) {
            setControlsDisabled(true);
            targetCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least one completed scan is needed for a timeline", false);
            return;
        }

        setControlsDisabled(false);
        targetCombo.setItems(FXCollections.observableArrayList(targets));
        String target = selectedTarget != null && targets.contains(selectedTarget)
                ? selectedTarget
                : newestCompletedTarget(scans);
        targetCombo.setValue(target);
        startLoad(target);
    }

    private void startLoad(String target) {
        selectedTarget = target;
        clearMessage();
        loadButton.setDisable(true);
        targetCombo.setDisable(true);
        timelineSlider.setDisable(true);
        track = Timelines.track(loadedScans, target, zone);
        busy = true;

        List<Long> scanIds = track.scanIds();
        Task<Map<Long, List<FindingSnapshot>>> task = new Task<>() {
            @Override
            protected Map<Long, List<FindingSnapshot>> call() throws ScanArchiveException {
                Map<Long, List<FindingSnapshot>> collected = new HashMap<>();
                for (long scanId : scanIds) {
                    collected.put(scanId, List.copyOf(history.listFindings(scanId)));
                }
                return Map.copyOf(collected);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            findingsByScanId = task.getValue();
            onTrackLoaded();
        });

        task.setOnFailed(event -> {
            busy = false;
            setControlsDisabled(false);
            showMessage("could not load the timeline for this target", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    private void onTrackLoaded() {
        setControlsDisabled(false);
        truncationLabel.setText(Timelines.truncationNote(track));

        int newest = Math.max(0, track.size() - 1);
        // §4.2 trap 1: park the value, grow/shrink max, then set the real value.
        timelineSlider.setValue(0);
        timelineSlider.setMax(track.sliderMax());
        timelineSlider.setValue(newest);

        renderedIndex = -1;
        if (!track.isEmpty()) {
            renderPosition(newest);
        }
        timelineSlider.setDisable(track.size() <= 1);
    }

    /** Pure re-render from already-prefetched data -- zero I/O (plan §0.3, §4.1 step 7). */
    private void renderPosition(int index) {
        positionLabel.setText(Timelines.positionLabel(track, index));
        targetLabel.setText(track.target());
        List<FindingSnapshot> findings = findingsByScanId.get(track.scanIdAt(index));
        findingsTable.setItems(FXCollections.observableArrayList(SnapshotRows.of(findings)));
        summaryLabel.setText(ChartData.summaryLine(findings));
        renderedIndex = index;
    }

    private static String newestCompletedTarget(List<ScanSummary> scans) {
        for (ScanSummary scan : scans) {
            if (scan.complete()) {
                return scan.target();
            }
        }
        return null;
    }

    private void clearTimelineDisplay() {
        track = EMPTY_TRACK;
        findingsByScanId = Map.of();
        renderedIndex = -1;
        positionLabel.setText(null);
        targetLabel.setText(null);
        summaryLabel.setText(null);
        findingsTable.setItems(FXCollections.observableArrayList());
        timelineSlider.setValue(0);
        timelineSlider.setMax(0);
        timelineSlider.setDisable(true);
    }

    private void setControlsDisabled(boolean disabled) {
        loadButton.setDisable(disabled);
        targetCombo.setDisable(disabled);
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
