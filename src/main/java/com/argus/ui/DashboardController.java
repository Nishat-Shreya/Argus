package com.argus.ui;

import com.argus.core.ScanAlert;
import com.argus.core.ScanArchive;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanComparison;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

/**
 * Dashboard screen. Field wiring, validation dispatch, and marshalling coordinator events onto
 * the FX thread. No scan logic, no threading policy, no domain rules — those are
 * {@link DashboardValidation} and {@link ScanCoordinator} (the {@code LoginController}
 * precedent, P1-05 §7.14).
 */
public final class DashboardController {

    private static final int LOG_CAPACITY = 2000;

    @FXML
    private HBox toolbar;
    @FXML
    private TextField targetField;
    @FXML
    private Button scanButton;
    @FXML
    private Button pauseButton;
    @FXML
    private Button cancelButton;
    @FXML
    private Button keysButton;
    @FXML
    private Button diffButton;
    @FXML
    private Button chartsButton;
    @FXML
    private Button graphButton;
    @FXML
    private Button timelineButton;
    @FXML
    private Button notificationsButton;
    @FXML
    private Circle liveDot;
    @FXML
    private Label messageLabel;
    @FXML
    private TableView<FindingRow> findingsTable;
    @FXML
    private ListView<String> workerList;
    @FXML
    private ListView<String> logConsole;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLine;

    private final ObservableList<String> workerItems = FXCollections.observableArrayList();
    private final ObservableList<String> logItems = FXCollections.observableArrayList();
    private final Map<String, Integer> workerRowIndex = new LinkedHashMap<>();

    private ScanCoordinator coordinator;
    private FadeTransition liveDotPulse;

    /** FX-thread-confined; set in {@link #initialize()}. Construction does no I/O (the {@code
     *  ScanArchive} contract), so this is legal on the FX thread. */
    private ScanHistory history;

    /** FX-thread-confined; injected by {@code App} after unlock. Defaults to the no-op fallback
     *  so a scan finishing before injection (or in a test) never touches a real channel. Renamed
     *  from P3-02's {@code notifier}/{@code setDesktopNotifier(DesktopNotifier)} (plan §3.6,
     *  R5): fan-out to more than one channel means the desktop tray can no longer be the only
     *  path a finished scan's alert takes. */
    private AlertChannel channels = AlertChannels.none();

    /** FX-thread-confined; opens the key-vault panel. Never a Vault here (P1-06's decision). */
    private Runnable onOpenKeySettings;

    /** FX-thread-confined; opens the scan diff panel. */
    private Runnable onOpenDiff;

    /** FX-thread-confined; opens the charts panel. */
    private Runnable onOpenCharts;

    /** FX-thread-confined; opens the network graph panel. */
    private Runnable onOpenGraph;

    /** FX-thread-confined; opens the timeline panel. */
    private Runnable onOpenTimeline;

    /** FX-thread-confined; opens the notifications settings panel. */
    private Runnable onOpenNotificationSettings;

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

        workerList.setItems(workerItems);
        logConsole.setItems(logItems);

        coordinator = new ScanCoordinator(new ScanEventListener() {
            @Override
            public void onScanStarted(ScanPlan plan, List<String> jobNames) {
                Platform.runLater(() -> appendLog("scan started · target " + plan.target()
                        + " · " + jobNames.size() + " workers"));
            }

            @Override
            public void onLog(String line) {
                Platform.runLater(() -> appendLog(line));
            }

            @Override
            public void onWorkerStatus(WorkerStatus status) {
                Platform.runLater(() -> updateWorkerRow(status));
            }

            @Override
            public void onFindings(List<FindingRow> batch) {
                Platform.runLater(() -> findingsTable.getItems().addAll(batch));
            }

            @Override
            public void onProgress(ScanProgress progress) {
                Platform.runLater(() -> progressBar.setProgress(progress.fraction()));
            }

            @Override
            public void onScanFinished(ScanOutcome outcome) {
                Platform.runLater(() -> onScanFinishedOnFxThread(outcome));
            }
        }, new DefaultScanJobFactory(), ScanArchive.atDefaultLocation()::save);

        history = ScanHistory.atDefaultLocation();
    }

    @FXML
    private void onScan() {
        DashboardValidation.Result result = DashboardValidation.check(targetField.getText());
        if (!result.valid()) {
            showMessage(result.message());
            AnimationUtils.shake(toolbar);
            return;
        }

        clearMessage();
        findingsTable.getItems().clear();
        logItems.clear();
        workerItems.clear();
        workerRowIndex.clear();
        progressBar.setProgress(0.0);
        statusLine.setText("running");
        appendLog("cleared");

        scanButton.setDisable(true);
        pauseButton.setDisable(false);
        pauseButton.setText("pause");
        cancelButton.setDisable(false);
        targetField.setEditable(false);

        startLiveDot();
        coordinator.start(ScanPlan.of(result.target()));
    }

    @FXML
    private void onPauseResume() {
        if (coordinator.isPaused()) {
            coordinator.resume();
            pauseButton.setText("pause");
            appendLog("scan resumed");
        } else {
            coordinator.pause();
            pauseButton.setText("resume");
            appendLog("scan paused · probes already in flight will finish; "
                    + "new results are held until resume");
        }
    }

    @FXML
    private void onCancel() {
        appendLog("cancel requested · in-flight probes stop within the connect timeout");
        coordinator.cancel();
    }

    /** Injected by App: opens the key-vault settings panel. */
    public void setOpenKeySettingsHandler(Runnable handler) {
        this.onOpenKeySettings = handler;
    }

    @FXML
    private void onOpenKeys() {
        if (onOpenKeySettings != null) {
            onOpenKeySettings.run();
        }
    }

    /** Injected by App: opens the scan diff panel. */
    public void setOpenDiffHandler(Runnable handler) {
        this.onOpenDiff = handler;
    }

    @FXML
    private void onOpenDiff() {
        if (onOpenDiff != null) {
            onOpenDiff.run();
        }
    }

    /** Injected by App: opens the charts panel. */
    public void setOpenChartsHandler(Runnable handler) {
        this.onOpenCharts = handler;
    }

    @FXML
    private void onOpenCharts() {
        if (onOpenCharts != null) {
            onOpenCharts.run();
        }
    }

    /** Injected by App: opens the network graph panel. */
    public void setOpenGraphHandler(Runnable handler) {
        this.onOpenGraph = handler;
    }

    @FXML
    private void onOpenGraph() {
        if (onOpenGraph != null) {
            onOpenGraph.run();
        }
    }

    /** Injected by App: opens the timeline panel. */
    public void setOpenTimelineHandler(Runnable handler) {
        this.onOpenTimeline = handler;
    }

    @FXML
    private void onOpenTimeline() {
        if (onOpenTimeline != null) {
            onOpenTimeline.run();
        }
    }

    /** Injected by App: opens the notifications settings panel. */
    public void setOpenNotificationSettingsHandler(Runnable handler) {
        this.onOpenNotificationSettings = handler;
    }

    @FXML
    private void onOpenNotificationSettings() {
        if (onOpenNotificationSettings != null) {
            onOpenNotificationSettings.run();
        }
    }

    /** Called by {@code App.stop()}. Closes the coordinator. Idempotent. */
    public void shutdown() {
        stopLiveDot();
        coordinator.close();
    }

    /** Injected by App: the alert fan-out (P3-03 §3.6). REPLACES P3-02's
     *  {@code setDesktopNotifier(DesktopNotifier)} — fan-out to more than one channel means the
     *  desktop tray can no longer be the only path a finished scan's alert takes (R5). */
    public void setAlertChannel(AlertChannel channels) {
        this.channels = channels;
    }

    /** Package-private test seam: swaps in a {@link ScanHistory} without going through
     *  {@link #initialize()} (P2-09's precedent for FX-thread-confined test doubles). */
    void setHistoryForTest(ScanHistory history) {
        this.history = history;
    }

    private void onScanFinishedOnFxThread(ScanOutcome outcome) {
        stopLiveDot();
        scanButton.setDisable(false);
        pauseButton.setDisable(true);
        pauseButton.setText("pause");
        cancelButton.setDisable(true);
        targetField.setEditable(true);

        String resultWord = switch (outcome.result()) {
            case COMPLETED -> "completed";
            case COMPLETED_WITH_ERRORS -> "completed with errors";
            case CANCELLED -> "cancelled";
        };
        String savedWord = outcome.saved()
                ? "saved #" + outcome.savedScanId()
                : "NOT SAVED — see log";
        appendLog("scan finished · " + outcome.findingsDelivered() + " findings · " + resultWord);
        statusLine.setText(resultWord + " · " + savedWord);

        maybeNotify(outcome);
    }

    /**
     * P3-02 §4.1: gate 1 is checked here, instantly, on the FX thread. Everything past that —
     * the blocking {@code ScanHistory} read and the diff — runs on a dedicated daemon thread, so
     * a scan finishing never blocks the FX thread on the database. {@code ScanCoordinator} is
     * untouched: this reads already-persisted history one step after the coordinator's own
     * final event, not a new hook into its supervisor loop.
     */
    private void maybeNotify(ScanOutcome outcome) {
        if (!ScanNotifications.eligible(outcome)) {
            return;
        }
        String target = outcome.run().target();
        long savedScanId = outcome.savedScanId();

        Task<Optional<ScanAlert>> task = new Task<>() {
            @Override
            protected Optional<ScanAlert> call() throws ScanArchiveException {
                List<ScanSummary> all = history.listScans();
                Optional<Long> baseline =
                        ScanNotifications.baselineScanId(all, target, savedScanId);
                if (baseline.isEmpty()) {
                    return Optional.empty();
                }
                ScanComparison comparison = history.compare(baseline.get(), savedScanId);
                return ScanNotifications.alertFor(comparison);
            }
        };

        task.setOnSucceeded(event -> task.getValue().ifPresent(alert -> {
            channels.deliver(alert);
            appendLog("notification · " + ScanNotifications.desktopNotification(alert).caption());
        }));

        task.setOnFailed(event -> appendLog(
                "notification check skipped · " + task.getException().getMessage()));

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateWorkerRow(WorkerStatus status) {
        String line = (status.threadName() == null ? "(not yet started)" : status.threadName())
                + " · " + status.jobName() + " · " + status.state().name().toLowerCase()
                + " · " + status.findingsPublished() + " findings";
        Integer index = workerRowIndex.get(status.jobName());
        if (index == null) {
            workerRowIndex.put(status.jobName(), workerItems.size());
            workerItems.add(line);
        } else {
            workerItems.set(index, line);
        }
    }

    private void appendLog(String line) {
        logItems.add(line);
        while (logItems.size() > LOG_CAPACITY) {
            logItems.remove(0);
        }
        logConsole.scrollTo(logItems.size() - 1);
    }

    private void startLiveDot() {
        liveDotPulse = AnimationUtils.pulseDot(liveDot);
    }

    private void stopLiveDot() {
        if (liveDotPulse != null) {
            liveDotPulse.stop();
            liveDotPulse = null;
        }
        liveDot.setOpacity(1);
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
}
