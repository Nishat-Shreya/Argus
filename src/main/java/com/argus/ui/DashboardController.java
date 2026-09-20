package com.argus.ui;

import com.argus.core.IntelArchive;
import com.argus.core.IntelReport;
import com.argus.core.IntelSubject;
import com.argus.core.KevCatalog;
import com.argus.core.KevCatalogException;
import com.argus.core.KevCatalogLoader;
import com.argus.core.KevMatchResult;
import com.argus.core.KevScorer;
import com.argus.core.ScanAlert;
import com.argus.core.ScanArchive;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanComparison;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import com.argus.core.ScheduledScan;
import com.argus.core.ScheduledScanArchive;
import com.argus.core.ThreatIntelClient;
import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * Dashboard screen. Field wiring, validation dispatch, and marshalling coordinator events onto
 * the FX thread. No scan logic, no threading policy, no domain rules — those are
 * {@link DashboardValidation} and {@link ScanCoordinator} (the {@code LoginController}
 * precedent, P1-05 §7.14).
 */
public final class DashboardController {

    private static final System.Logger LOGGER =
            System.getLogger(DashboardController.class.getName());

    private static final int LOG_CAPACITY = 2000;

    /** How often the recurring-scan check runs (plan: an in-app timer, not an OS-level
     *  scheduler -- schedules only fire while Argus is running). */
    private static final long SCHEDULER_PERIOD_SECONDS = 30;

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
    private Button reportButton;
    @FXML
    private Button notificationsButton;
    @FXML
    private Button findingsButton;
    @FXML
    private Button scheduledScansButton;
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
    @FXML
    private VBox queueDropZone;
    @FXML
    private ListView<String> queueList;
    @FXML
    private Button runQueueButton;
    @FXML
    private Button removeQueuedButton;
    @FXML
    private Label queueStatusLabel;

    private final ObservableList<String> workerItems = FXCollections.observableArrayList();
    private final ObservableList<String> logItems = FXCollections.observableArrayList();
    private final Map<String, Integer> workerRowIndex = new LinkedHashMap<>();

    /** FX-thread-confined pending scan-target queue (plan §3.5, §4.1) -- NOT the invariant-4
     *  producer-consumer pipeline; no scan thread ever touches it. */
    private final TargetQueue targetQueue = new TargetQueue();
    private final ObservableList<String> queueItems = FXCollections.observableArrayList();

    /** FX-thread-confined, not volatile -- read-then-written, safe only because every access is
     *  confined to the FX thread (plan §4.3). True while {@code run queue} is driving the
     *  coordinator through successive targets. */
    private boolean queueRunning;

    /** FX-thread-confined, not volatile. Set once by {@link #shutdown()} so a {@code runLater}
     *  queue-advance still in flight during {@code App.stop()} cannot call {@code start()} on a
     *  closed coordinator (plan §3.6 point 4, the P3-02 shutdown-race lesson). */
    private boolean queueStopped;

    /** FX-thread-confined. The currently-running scan's normalized target, or {@code null} when
     *  idle; it occupies a queue slot so a re-drop of the running target is a duplicate. */
    private String activeTarget;

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

    /** FX-thread-confined; opens the report export panel. */
    private Runnable onOpenReport;

    /** FX-thread-confined; opens the notifications settings panel. */
    private Runnable onOpenNotificationSettings;

    /** FX-thread-confined; opens the findings detail panel (P3-06, the 8th nav button). */
    private Runnable onOpenFindingsDetail;

    /** FX-thread-confined; opens the scheduled-scans panel (P3-08, the 9th nav button). */
    private Runnable onOpenScheduledScans;

    /** FX-thread-confined; injected by App after unlock. Defaults to null: the scheduler tick
     *  no-ops until it is set, which happens synchronously right after this controller loads
     *  and well before the dashboard is ever shown. */
    private ScheduledScanArchive scheduledScans;

    /** One background daemon thread, ticking every {@link #SCHEDULER_PERIOD_SECONDS} (plan:
     *  the in-app scheduler for P3-08) -- the {@code WebhookAlertChannel} precedent for a
     *  persistent pool needing the full invariant-6 shutdown ladder, not a one-shot Task. */
    private ScheduledExecutorService schedulerPool;

    /** FX-thread-confined; injected by App after unlock (P3-15). Null until then, and null
     *  forever if {@code -Dargus.intel=false} -- both are "enrichment disabled," not an error. */
    private ThreatIntelClient intelClient;

    /** FX-thread-confined; constructed once in {@link #initialize()} (cheap, no I/O -- the
     *  {@code ScanHistory} precedent). */
    private IntelArchive intelArchive;

    /** Written exactly once, on the FX thread, by the one-shot KEV-catalog-load {@code Task}
     *  started from {@link #setIntelClient}; read later from a DIFFERENT, always-LATER-started
     *  background thread per enrichment run. No volatile needed: the write and every later
     *  {@code Thread.start()} both happen on the FX thread, so plain FX-thread program order
     *  already orders the write before any read (the {@code history} field precedent in
     *  {@link #maybeNotify}). Null (KEV scoring skipped that run) until the catalog loads, or
     *  forever if the load fails -- no retry (the {@code KevCatalogLoader} contract). */
    private KevScorer kevScorer;

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

        queueList.setItems(queueItems);
        queueList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> updateQueueControls());
        queueDropZone.setOnDragOver(this::handleDragOver);
        queueDropZone.setOnDragEntered(
                event -> queueDropZone.getStyleClass().add("drop-zone-active"));
        queueDropZone.setOnDragExited(
                event -> queueDropZone.getStyleClass().remove("drop-zone-active"));
        queueDropZone.setOnDragDropped(this::handleDragDropped);

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
        updateQueueControls();

        history = ScanHistory.atDefaultLocation();
        intelArchive = IntelArchive.atDefaultLocation();

        schedulerPool = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "argus-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        schedulerPool.scheduleWithFixedDelay(this::checkScheduledScans,
                SCHEDULER_PERIOD_SECONDS, SCHEDULER_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @FXML
    private void onScan() {
        DashboardValidation.Result result = DashboardValidation.check(targetField.getText());
        if (!result.valid()) {
            showMessage(result.message());
            AnimationUtils.shake(toolbar);
            return;
        }
        startScan(result.target());
    }

    /**
     * The single scan-start path shared by the typed {@link #onScan()} route and queue
     * advancement (plan §3.6, §0.3 point 3) -- so the reset/disable/animate sequence cannot
     * drift between them. {@code normalizedTarget} is already validated: by
     * {@link DashboardValidation} for the typed path, by {@link DroppedTargets#parse} (which
     * reuses the same {@code core.DomainName} rule) for the queued path.
     */
    private void startScan(String normalizedTarget) {
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
        targetField.setText(normalizedTarget);

        activeTarget = normalizedTarget;
        updateQueueControls();

        startLiveDot();
        coordinator.start(ScanPlan.of(normalizedTarget));
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

    /** Explicit start (plan §0.3): a drag-and-drop gesture only fills the queue, never starts
     *  scanning it. Requires: not shut down, no scan already running, queue non-empty. */
    @FXML
    private void onRunQueue() {
        if (queueStopped || coordinator.isRunning() || targetQueue.isEmpty()) {
            return;
        }
        queueRunning = true;
        targetQueue.poll().ifPresent(this::startScan);
        refreshQueueView();
        updateQueueControls();
    }

    /** Removes the selected pending target -- the minimum correction affordance for a mistaken
     *  drop (plan §7 R4). No drag-to-reorder, no clear-all. */
    @FXML
    private void onRemoveQueued() {
        String selected = queueList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        targetQueue.remove(selected);
        refreshQueueView();
        updateQueueControls();
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

    /** Injected by App: opens the report export panel. */
    public void setOpenReportHandler(Runnable handler) {
        this.onOpenReport = handler;
    }

    @FXML
    private void onOpenReport() {
        if (onOpenReport != null) {
            onOpenReport.run();
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

    /** Injected by App: opens the findings detail panel. */
    public void setOpenFindingsDetailHandler(Runnable handler) {
        this.onOpenFindingsDetail = handler;
    }

    @FXML
    private void onOpenFindingsDetail() {
        if (onOpenFindingsDetail != null) {
            onOpenFindingsDetail.run();
        }
    }

    /** Injected by App: opens the scheduled-scans panel. */
    public void setOpenScheduledScansHandler(Runnable handler) {
        this.onOpenScheduledScans = handler;
    }

    @FXML
    private void onOpenScheduledScans() {
        if (onOpenScheduledScans != null) {
            onOpenScheduledScans.run();
        }
    }

    /** Injected by App immediately after this controller loads. */
    public void setScheduledScans(ScheduledScanArchive archive) {
        this.scheduledScans = archive;
    }

    /** Injected by App immediately after unlock (P3-15). Honors {@code -Dargus.intel=false} as
     *  an opt-out (a full target queue would otherwise fire real, unmetered third-party API
     *  calls after every single scan): when disabled, the client is simply never stored, so
     *  every enrichment call site's existing null-check makes it a no-op everywhere at once.
     *  Also kicks off the one-shot KEV catalog load. */
    public void setIntelClient(ThreatIntelClient client) {
        if ("false".equalsIgnoreCase(System.getProperty("argus.intel"))) {
            return;
        }
        this.intelClient = client;
        loadKevCatalogOnce();
    }

    /** One-shot background load of the public CISA KEV feed (plan: a fresh {@code KevScorer}
     *  per app run, never refreshed -- {@code KevScorer}'s own contract). No retry: a failed
     *  load just means KEV scoring is skipped for the rest of this session, exactly as {@code
     *  KevCatalogLoader} intends (no backoff, no pacing, of any kind). */
    private void loadKevCatalogOnce() {
        Task<KevCatalog> task = new Task<>() {
            @Override
            protected KevCatalog call() throws KevCatalogException, InterruptedException {
                return new KevCatalogLoader().load();
            }
        };
        task.setOnSucceeded(event -> {
            kevScorer = new KevScorer(task.getValue());
            appendLog("KEV catalog loaded");
        });
        task.setOnFailed(event -> appendLog(
                "KEV catalog load failed · intel enrichment continues without KEV scoring · "
                        + task.getException().getMessage()));

        Thread thread = new Thread(task, "argus-kev-catalog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Called by {@code App.stop()}. Stops the scheduler first -- {@code queueStopped = true}
     *  makes any tick already in flight a no-op, then the pool itself is shut down (invariant
     *  6's ladder) so no new tick can fire -- before closing the coordinator. Idempotent. */
    public void shutdown() {
        queueStopped = true;
        shutdownSchedulerPool();
        stopLiveDot();
        coordinator.close();
    }

    private void shutdownSchedulerPool() {
        schedulerPool.shutdown();
        try {
            if (!schedulerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                schedulerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            schedulerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        maybeEnrichIntel(outcome);
        advanceQueue(outcome);
    }

    /**
     * Sequential queue execution (plan §0.3): {@code COMPLETED} and
     * {@code COMPLETED_WITH_ERRORS} advance to the next pending target; {@code CANCELLED} halts
     * the queue and retains the pending targets. Called once, at the end of
     * {@link #onScanFinishedOnFxThread}, after {@link #maybeNotify}. {@code ScanCoordinator} is
     * untouched: this reacts to its existing finished-scan callback, exactly as the typed
     * {@code scan} button's next click already could.
     */
    private void advanceQueue(ScanOutcome outcome) {
        activeTarget = null;
        if (queueStopped || !queueRunning) {
            updateQueueControls();
            return;
        }
        if (!TargetQueue.advancesAfter(outcome.result())) {
            queueRunning = false;
            String halted =
                    "queue halted · cancelled · " + targetQueue.size() + " target(s) remaining";
            queueStatusLabel.setText(halted);
            appendLog(halted);
            refreshQueueView();
            updateQueueControls();
            return;
        }
        Optional<String> next = targetQueue.poll();
        if (next.isEmpty()) {
            queueRunning = false;
            queueStatusLabel.setText("queue finished");
            appendLog("queue finished");
            refreshQueueView();
            updateQueueControls();
            return;
        }
        refreshQueueView();
        startScan(next.get());
    }

    /**
     * DRAG_OVER fires repeatedly during the gesture; nothing blocking may happen here. Accepts
     * {@code COPY} only -- never {@code ANY}, never {@code MOVE} (plan §0.2): accepting
     * {@code MOVE} would let the source application delete the operator's dropped file.
     */
    private void handleDragOver(DragEvent event) {
        Dragboard board = event.getDragboard();
        if (board.hasFiles() || board.hasString()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    /**
     * The four in-memory steps of plan §4.2, in order: (1) snapshot the ENTIRE dragboard into a
     * toolkit-free {@link DroppedContent} -- ALL dragboard reads happen here, because the
     * javadoc states no dragboard access can happen after {@code setDropCompleted}; (2) complete
     * and consume the event; (3) hand the already-captured snapshot to {@link #acceptDrop}; (4)
     * clear the hover highlight.
     */
    private void handleDragDropped(DragEvent event) {
        DroppedContent content = contentOf(event.getDragboard());
        event.setDropCompleted(true);
        event.consume();
        acceptDrop(content);
        queueDropZone.getStyleClass().remove("drop-zone-active");
    }

    /**
     * Text path: parsed synchronously on the FX thread -- a regex split plus a bounded LDH
     * regex per token, over a blob already capped by {@code DroppedTargets.MAX_TOKENS}, is
     * microseconds (plan §4.2). File path: read on a background daemon {@code Task} --
     * {@code Files.readAllBytes} on an arbitrary dropped path (UNC share, network drive,
     * removable media) can take seconds and must never run on the FX thread (invariant 3).
     */
    private void acceptDrop(DroppedContent content) {
        if (content.isEmpty()) {
            return;
        }
        List<Path> files = DroppedTargets.filesOf(content);
        if (!files.isEmpty()) {
            Task<String> task = new Task<>() {
                @Override
                protected String call() throws IOException {
                    return DroppedTargets.read(files);
                }
            };
            task.setOnSucceeded(event -> admit(DroppedTargets.parse(task.getValue())));
            task.setOnFailed(event -> {
                Throwable failure = task.getException();
                String message = failure == null ? "unknown error" : failure.getMessage();
                queueStatusLabel.setText("drop ignored · " + message);
                appendLog("drop ignored · " + message);
            });
            Thread thread = new Thread(task, "argus-target-drop");
            thread.setDaemon(true);
            thread.start();
            return;
        }
        String text = DroppedTargets.textOf(content);
        if (!text.isBlank()) {
            admit(DroppedTargets.parse(text));
        }
    }

    /** Offers a parsed drop to the queue, then renders the outcome -- one place for admit +
     *  render + log (plan §3.6). */
    private void admit(TargetDrop drop) {
        QueueAdmission admission = targetQueue.admit(drop, activeTarget);
        String description = admission.describe();
        queueStatusLabel.setText(description);
        appendLog("queue · " + description);
        refreshQueueView();
        updateQueueControls();
    }

    private void refreshQueueView() {
        queueItems.setAll(targetQueue.pending());
    }

    private void updateQueueControls() {
        runQueueButton.setDisable(
                queueStopped || coordinator.isRunning() || targetQueue.isEmpty());
        removeQueuedButton.setDisable(queueList.getSelectionModel().getSelectedItem() == null);
    }

    /** The ONLY {@code Dragboard}-aware code in this class (plan §3.6): converts a live
     *  {@code Dragboard} into a toolkit-free snapshot. Files win over text (plan §0.2). */
    private static DroppedContent contentOf(Dragboard board) {
        if (board.hasFiles()) {
            List<Path> paths = new ArrayList<>();
            for (File file : board.getFiles()) {
                paths.add(file.toPath());
            }
            return DroppedContent.ofFiles(paths);
        }
        if (board.hasString()) {
            return DroppedContent.ofText(board.getString());
        }
        return DroppedContent.none();
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

    /**
     * P3-15: after a saved, non-cancelled scan, fans the scan's domain target out across the
     * configured intel sources and scores any CVEs found against the KEV catalog, entirely on a
     * background daemon thread -- never the FX thread (invariant 3). No-op if no {@code
     * ThreatIntelClient} was ever injected (locked vault, or {@code -Dargus.intel=false}) or the
     * scan was not saved (nothing to persist against). Shodan/AbuseIPDB are IP-only and a scan
     * target is always a domain (unchanged since P1-02), so {@code ThreatIntelClient}'s own
     * {@code supports()} routing already reports them as {@code UNSUPPORTED_SUBJECT} -- a
     * visible, honest gap, not a silent one, and not something this method works around.
     */
    private void maybeEnrichIntel(ScanOutcome outcome) {
        if (intelClient == null || !outcome.saved()
                || !TargetQueue.advancesAfter(outcome.result())) {
            return;
        }
        String target = outcome.run().target();
        long scanId = outcome.savedScanId();
        ThreatIntelClient client = intelClient;
        KevScorer scorer = kevScorer;
        IntelArchive archive = intelArchive;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws InterruptedException, ScanArchiveException {
                IntelReport report = client.enrich(IntelSubject.domain(target));
                KevMatchResult kevMatch = scorer == null
                        ? KevMatchResult.none() : scorer.score(report.allCveIds());
                archive.save(scanId, report, kevMatch);
                return null;
            }
        };

        task.setOnSucceeded(event -> appendLog("intel enrichment saved for scan #" + scanId));
        task.setOnFailed(event -> appendLog(
                "intel enrichment skipped · " + task.getException().getMessage()));

        Thread intelThread = new Thread(task, "argus-intel-enrich");
        intelThread.setDaemon(true);
        intelThread.start();
    }

    /**
     * Runs on {@code argus-scheduler}, NEVER the FX thread. Reads due schedules, records each
     * one as run BEFORE asking the FX thread to actually start it -- so a tick landing again
     * before the {@code runLater} below is processed can never re-trigger the same due
     * schedule twice (plan: this trades "next run advances at trigger time" for "no
     * double-fire," the simplest correct choice for a single-operator tool). Any failure here
     * is logged and retried on the next tick -- a scheduling hiccup must never crash this
     * thread or the scan pipeline.
     */
    private void checkScheduledScans() {
        if (scheduledScans == null) {
            return;
        }
        List<ScheduledScan> due;
        try {
            due = scheduledScans.due(Instant.now());
        } catch (ScanArchiveException e) {
            LOGGER.log(Level.WARNING, "could not read scheduled scans", e);
            return;
        }
        for (ScheduledScan schedule : due) {
            try {
                scheduledScans.recordRun(schedule.id(), schedule.intervalMinutes(), Instant.now());
            } catch (ScanArchiveException e) {
                LOGGER.log(Level.WARNING,
                        "could not record run for scheduled scan " + schedule.id(), e);
                continue;
            }
            String target = schedule.target();
            Platform.runLater(() -> triggerScheduledScan(target));
        }
    }

    /**
     * FX thread. Reuses the exact same start path as the typed {@code scan} button and queue
     * advancement: if nothing is running, start it now; if a scan is already running, admit it
     * to the existing target queue so it runs once the current scan finishes (plan: reuse
     * {@code TargetQueue}/{@code startScan}, no second scan-launching path). No-op once the
     * dashboard is shutting down.
     */
    private void triggerScheduledScan(String target) {
        if (queueStopped) {
            return;
        }
        if (coordinator.isRunning()) {
            targetQueue.admit(new TargetDrop(List.of(target), List.of()), activeTarget);
            refreshQueueView();
            updateQueueControls();
            appendLog("scheduled scan queued · " + target);
        } else {
            appendLog("scheduled scan starting · " + target);
            startScan(target);
        }
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
