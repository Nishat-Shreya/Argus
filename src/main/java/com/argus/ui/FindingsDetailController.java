package com.argus.ui;

import com.argus.core.AnnotationArchive;
import com.argus.core.FindingSnapshot;
import com.argus.core.FindingNote;
import com.argus.core.FindingTag;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import com.argus.core.TagArchive;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Findings detail screen (spec.md #5, plan §3.4). Field wiring, three {@code Task} dispatches,
 * {@link #renderSelection}, and the click-to-expand focus listener only — every rule worth
 * getting wrong lives in {@link NoteRows}, {@link Notes}, {@link NoteValidation},
 * {@link NoteDeletion}, {@link ScanChoices} and {@link ScanHistory}/{@link AnnotationArchive},
 * all directly testable.
 *
 * "Related CVEs" (spec.md's screen-#5 wording) is descoped to P3-16 (plan §0.4): no intel/KEV
 * result is ever persisted, so this screen ships raw data plus the annotation field only —
 * {@code unsourceableLabel} carries the on-screen note explaining this.
 */
public final class FindingsDetailController {

    private static final double COLLAPSED_HEIGHT = 34;
    private static final double EXPANDED_HEIGHT = 132;

    /** {@link #detailSplit}'s divider position while a finding is selected -- the layout-cleanup
     *  batch's fix for the empty panel {@code SplitPane} always reserved for {@code detailPane},
     *  even while its content was invisible/unmanaged. */
    private static final double DETAIL_SPLIT_OPEN = 0.45;
    private static final double DETAIL_SPLIT_COLLAPSED = 1.0;

    @FXML
    private VBox root;
    @FXML
    private DatePicker datePicker;
    @FXML
    private ComboBox<ScanChoice> scanCombo;
    @FXML
    private Button loadButton;
    @FXML
    private Button backButton;
    @FXML
    private Label hiddenLabel;
    @FXML
    private Label messageLabel;
    @FXML
    private Label unsourceableLabel;
    @FXML
    private ComboBox<String> tagFilterCombo;
    @FXML
    private TableView<NoteRow> findingsTable;
    @FXML
    private SplitPane detailSplit;
    @FXML
    private VBox detailPane;
    @FXML
    private VBox detailLinesBox;
    @FXML
    private ListView<FindingTag> tagList;
    @FXML
    private TextField tagField;
    @FXML
    private Button addTagButton;
    @FXML
    private Button removeTagButton;
    @FXML
    private Label tagCountLabel;
    @FXML
    private ListView<NoteEntry> notesList;
    @FXML
    private TextArea noteField;
    @FXML
    private Button addNoteButton;
    @FXML
    private Button deleteNoteButton;
    @FXML
    private Label noteCountLabel;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private AnnotationArchive notes;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private TagArchive tags;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /**
     * Single-flight guard; a background load, add or delete is in flight. FX-thread-confined,
     * not volatile -- it is read-then-written (a compound op), and confinement to the FX thread
     * is what makes that safe (the {@code ScanDiffController.busy} precedent, plan §5).
     */
    private boolean busy;

    /** The full, unfiltered load from the last {@link #refresh()}. FX-thread-confined. */
    private List<ScanSummary> loadedScans = List.of();

    /** {@code ScanChoices.selectable(loadedScans, ...)}, newest first — R8: reused
     *  byte-identical, no new selection rule. FX-thread-confined. */
    private List<ScanChoice> selectableChoices = List.of();

    /** {@code ZoneId.systemDefault()}, read once per {@link #refresh()} and passed explicitly
     *  into {@link Notes} -- never read by {@code Notes} itself. FX-thread-confined. */
    private ZoneId zone = ZoneId.systemDefault();

    /** The currently loaded scan, or {@code -1} before any load. FX-thread-confined. */
    private long selectedScanId = -1L;

    /** The currently selected finding's db id, or {@code -1} when nothing is selected.
     *  FX-thread-confined. */
    private long selectedFindingId = -1L;

    /**
     * Every note on every finding of {@link #selectedScanId}, prefetched by a background
     * {@code Task} (plan §4.1). Immutable ({@code Map.copyOf} of {@code List.copyOf}s) -- the
     * one cross-thread value, published from {@code Task.setOnSucceeded} (P3-01's rule).
     * FX-thread-confined.
     */
    private Map<Long, List<FindingNote>> notesByFindingId = Map.of();

    /** Whether {@code noteField} is currently expanded. FX-thread-confined, not volatile --
     *  read-then-written, safe only because it is confined to the FX thread (plan §3.4). */
    private boolean noteFieldExpanded;

    /**
     * Every tag assignment across every finding of {@link #selectedScanId}, prefetched by a
     * background {@code Task} (plan §4.1). Immutable, the {@link #notesByFindingId} twin.
     * FX-thread-confined.
     */
    private Map<Long, List<FindingTag>> tagsByFindingId = Map.of();

    /** The unfiltered rows of the current load -- what {@link #findingsTable} shows is
     *  {@code TagFilter.apply(allRows, tagsByFindingId, selectedTagName)} (plan §4.2, R2).
     *  FX-thread-confined. */
    private List<NoteRow> allRows = List.of();

    /** The current tag filter choice; {@code null} means {@link Tags#ALL_TAGS} (plan §3.4).
     *  FX-thread-confined. */
    private String selectedTagName;

    /** Set while {@link #tagFilterCombo}'s items are rebuilt programmatically, so that rebuild
     *  does not itself fire a filter pass (plan §3.4). FX-thread-confined, not volatile -- the
     *  set/read/clear sequence is confined to the FX thread. */
    private boolean suppressFilterEvents;

    @FXML
    @SuppressWarnings("unchecked")
    private void initialize() {
        TableColumn<NoteRow, String> typeColumn =
                (TableColumn<NoteRow, String>) findingsTable.getColumns().get(0);
        TableColumn<NoteRow, String> subjectColumn =
                (TableColumn<NoteRow, String>) findingsTable.getColumns().get(1);
        TableColumn<NoteRow, String> portColumn =
                (TableColumn<NoteRow, String>) findingsTable.getColumns().get(2);
        TableColumn<NoteRow, String> stateColumn =
                (TableColumn<NoteRow, String>) findingsTable.getColumns().get(3);
        typeColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().type()));
        subjectColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().subject()));
        portColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().port()));
        stateColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().state()));

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

        datePicker.setDayCellFactory(picker -> dayCell());
        datePicker.valueProperty().addListener((obs, oldDate, newDate) -> onDateChanged(newDate));

        findingsTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldRow, newRow) -> {
                    if (newRow == null) {
                        clearDetail();
                    } else {
                        renderSelection(newRow);
                    }
                });

        notesList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(NoteEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label timestamp = new Label(item.timestamp());
                    timestamp.getStyleClass().add("note-timestamp");
                    Label body = new Label(item.body());
                    body.setWrapText(true);
                    setGraphic(new VBox(2, timestamp, body));
                    setText(null);
                }
            }
        });
        notesList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldValue, newValue) -> deleteNoteButton.setDisable(newValue == null));

        tagList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(FindingTag item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label name = new Label(item.name());
                    name.getStyleClass().add("tag-name");
                    setGraphic(name);
                    setText(null);
                }
            }
        });
        tagList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldValue, newValue) -> removeTagButton.setDisable(newValue == null));

        tagFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (suppressFilterEvents) {
                return;
            }
            selectedTagName = newValue;
            applyTagFilter();
        });

        noteField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                expandNoteField();
            } else if (Notes.shouldCollapseOnFocusLost(noteField.getText())) {
                collapseNoteField();
            }
        });
    }

    /** Injected by App immediately after the FXML loads. */
    public void setHistory(ScanHistory injectedHistory) {
        this.history = injectedHistory;
    }

    /** Injected by App immediately after the FXML loads. */
    public void setNotes(AnnotationArchive injectedNotes) {
        this.notes = injectedNotes;
    }

    /** Injected by App immediately after the FXML loads. */
    public void setTags(TagArchive injectedTags) {
        this.tags = injectedTags;
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
        findingsTable.setItems(FXCollections.observableArrayList());
        clearDetail();
        setLoadControlsDisabled(true);
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
        ScanChoice choice = scanCombo.getValue();
        if (choice == null) {
            showMessage("choose a completed scan first", true);
            AnimationUtils.shake(root);
            return;
        }
        startLoad(choice.scanId());
    }

    @FXML
    private void onBack() {
        clearMessage();
        if (onClose != null) {
            onClose.run();
        }
    }

    @FXML
    private void onAddNote() {
        if (busy || selectedFindingId < 0) {
            return;
        }
        NoteValidation.Result result = NoteValidation.check(noteField.getText());
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(noteField);
            return;
        }

        long findingId = selectedFindingId;
        String body = result.body();
        Instant createdAt = Instant.now(); // THE clock fence (plan §0.2): exactly once, here.
        busy = true;
        clearMessage();
        setNoteControlsDisabled(true);

        Task<FindingNote> task = new Task<>() {
            @Override
            protected FindingNote call() throws ScanArchiveException {
                return notes.add(findingId, body, createdAt);
            }
        };

        task.setOnSucceeded(event -> {
            noteField.clear();
            collapseNoteField();
            refreshNotesForSelectedFinding();
        });

        task.setOnFailed(event -> {
            busy = false;
            setNoteControlsDisabled(false);
            showMessage("could not save that note", true);
            AnimationUtils.shake(noteField);
        });

        Thread thread = new Thread(task, "argus-annotations");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Operator-added requirement (CHANGED from the plan's R9 default of one-click delete): shows
     * a confirmation {@code Alert} before deleting a note. The actual gate is
     * {@link NoteDeletion#shouldDelete(boolean, boolean)}, a toolkit-free pure decision, so it is
     * unit-testable without driving a real dialog; this method's only job is to obtain the
     * operator's answer and wire it in.
     */
    @FXML
    private void onDeleteNote() {
        if (busy) {
            return;
        }
        NoteEntry selected = notesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        boolean confirmed = confirmDelete();
        if (!NoteDeletion.shouldDelete(true, confirmed)) {
            return;
        }
        startDelete(selected.noteId());
    }

    /** Shows this project's first modal dialog -- a delete confirmation {@code Alert}, styled
     *  through {@code Theme.applyTo(...)} on its dialog pane's scene (the App.java screen-swap
     *  call pattern), re-opening the P2-09 R4 popup-stylesheet-risk territory with the same
     *  care. Never called off the FX thread (only {@link #onDeleteNote()} calls it, an
     *  {@code @FXML} handler). */
    private boolean confirmDelete() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete this note? This cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("delete note");
        alert.setHeaderText(null);
        Scene alertScene = alert.getDialogPane().getScene();
        if (alertScene != null) {
            Theme.applyTo(alertScene);
        }
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    @FXML
    private void onAddTag() {
        if (busy || selectedFindingId < 0) {
            return;
        }
        TagValidation.Result result = TagValidation.check(tagField.getText());
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(tagField);
            return;
        }

        long findingId = selectedFindingId;
        String name = result.name();
        busy = true;
        clearMessage();
        setTagControlsDisabled(true);

        Task<FindingTag> task = new Task<>() {
            @Override
            protected FindingTag call() throws ScanArchiveException {
                return tags.add(findingId, name);
            }
        };

        task.setOnSucceeded(event -> {
            tagField.clear();
            refreshTagsForSelectedFinding();
        });

        task.setOnFailed(event -> {
            busy = false;
            setTagControlsDisabled(false);
            showMessage("could not save that tag", true);
            AnimationUtils.shake(tagField);
        });

        Thread thread = new Thread(task, "argus-tags");
        thread.setDaemon(true);
        thread.start();
    }

    /** No confirmation dialog on removal, unlike {@link #onDeleteNote()} (plan §0.4c, R6): an
     *  accidentally removed tag costs four keystrokes to re-type, and the operator sees the
     *  chip disappear immediately. */
    @FXML
    private void onRemoveTag() {
        if (busy) {
            return;
        }
        FindingTag selected = tagList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        startRemoveTag(selected.tagId());
    }

    private void startRemoveTag(long tagId) {
        long findingId = selectedFindingId;
        busy = true;
        clearMessage();
        setTagControlsDisabled(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws ScanArchiveException {
                return tags.remove(findingId, tagId);
            }
        };

        task.setOnSucceeded(event -> refreshTagsForSelectedFinding());

        task.setOnFailed(event -> {
            busy = false;
            setTagControlsDisabled(false);
            showMessage("could not remove that tag", true);
        });

        Thread thread = new Thread(task, "argus-tags");
        thread.setDaemon(true);
        thread.start();
    }

    private void startLoad(long scanId) {
        selectedScanId = scanId;
        selectedTagName = null;
        clearMessage();
        clearDetail();
        setLoadControlsDisabled(true);
        busy = true;

        Task<List<FindingSnapshot>> task = new Task<>() {
            @Override
            protected List<FindingSnapshot> call() throws ScanArchiveException {
                return history.listFindings(scanId);
            }
        };

        task.setOnSucceeded(event -> {
            allRows = NoteRows.of(task.getValue());
            applyTagFilter();
            loadNotesForScan(scanId);
        });

        task.setOnFailed(event -> {
            busy = false;
            setLoadControlsDisabled(false);
            showMessage("could not load that scan's findings", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-scan-history");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadNotesForScan(long scanId) {
        Task<List<FindingNote>> task = new Task<>() {
            @Override
            protected List<FindingNote> call() throws ScanArchiveException {
                return notes.listForScan(scanId);
            }
        };

        task.setOnSucceeded(event -> {
            notesByFindingId = Notes.byFinding(task.getValue());
            loadTagsForScan(scanId);
        });

        task.setOnFailed(event -> {
            busy = false;
            setLoadControlsDisabled(false);
            showMessage("could not load notes for that scan", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-annotations");
        thread.setDaemon(true);
        thread.start();
    }

    /** The load chain's third step (plan §4.3, R1): the only edit to shipped logic is that the
     *  {@code busy}/load-controls release moved here from {@link #loadNotesForScan}'s
     *  {@code setOnSucceeded} -- the same handoff {@link #startLoad} already performs to
     *  {@link #loadNotesForScan}. */
    private void loadTagsForScan(long scanId) {
        Task<List<FindingTag>> task = new Task<>() {
            @Override
            protected List<FindingTag> call() throws ScanArchiveException {
                return tags.listForScan(scanId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            setLoadControlsDisabled(false);
            tagsByFindingId = Tags.byFinding(task.getValue());
            populateTagFilterCombo();
            applyTagFilter();
        });

        task.setOnFailed(event -> {
            busy = false;
            setLoadControlsDisabled(false);
            showMessage("could not load tags for that scan", true);
            AnimationUtils.shake(root);
        });

        Thread thread = new Thread(task, "argus-tags");
        thread.setDaemon(true);
        thread.start();
    }

    private void startDelete(long noteId) {
        busy = true;
        clearMessage();
        setNoteControlsDisabled(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws ScanArchiveException {
                return notes.delete(noteId);
            }
        };

        task.setOnSucceeded(event -> refreshNotesForSelectedFinding());

        task.setOnFailed(event -> {
            busy = false;
            setNoteControlsDisabled(false);
            showMessage("could not delete that note", true);
        });

        Thread thread = new Thread(task, "argus-annotations");
        thread.setDaemon(true);
        thread.start();
    }

    /** Re-reads one finding's notes from the archive after an add or a delete succeeds, and
     *  merges the fresh list into {@link #notesByFindingId}. */
    private void refreshNotesForSelectedFinding() {
        long findingId = selectedFindingId;

        Task<List<FindingNote>> task = new Task<>() {
            @Override
            protected List<FindingNote> call() throws ScanArchiveException {
                return notes.listForFinding(findingId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            setNoteControlsDisabled(false);
            notesByFindingId = withFindingNotes(notesByFindingId, findingId, task.getValue());
            renderNotesList();
        });

        task.setOnFailed(event -> {
            busy = false;
            setNoteControlsDisabled(false);
            showMessage("could not refresh notes for this finding", true);
        });

        Thread thread = new Thread(task, "argus-annotations");
        thread.setDaemon(true);
        thread.start();
    }

    /** Re-reads one finding's tags from the archive after an add or a remove succeeds, and
     *  merges the fresh list into {@link #tagsByFindingId}, then re-derives
     *  {@link #tagFilterCombo}'s choices and re-applies the filter (plan §4.4, {@code
     *  loadTagsForScan}'s {@code populateTagFilterCombo(); applyTagFilter();} sequence): a tag
     *  *removal* can change whether the selected row still satisfies the current filter (e.g.
     *  removing the very tag the table is filtered on), so {@link #findingsTable}'s items must
     *  be re-derived here too. Re-filtering may clear the table selection and close the detail
     *  pane via the existing selection listener -- that is correct (plan §4.2). */
    private void refreshTagsForSelectedFinding() {
        long findingId = selectedFindingId;

        Task<List<FindingTag>> task = new Task<>() {
            @Override
            protected List<FindingTag> call() throws ScanArchiveException {
                return tags.listForFinding(findingId);
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            setTagControlsDisabled(false);
            tagsByFindingId = withFindingTags(tagsByFindingId, findingId, task.getValue());
            renderTagsList();
            populateTagFilterCombo();
            applyTagFilter();
        });

        task.setOnFailed(event -> {
            busy = false;
            setTagControlsDisabled(false);
            showMessage("could not refresh tags for this finding", true);
        });

        Thread thread = new Thread(task, "argus-tags");
        thread.setDaemon(true);
        thread.start();
    }

    /** Rebuilds {@link #tagFilterCombo}'s items from every tag currently prefetched, and
     *  re-selects the preserved choice (plan §4.4) -- {@link #suppressFilterEvents} guards the
     *  programmatic repopulate from firing a filter pass. */
    private void populateTagFilterCombo() {
        List<FindingTag> allTags = new ArrayList<>();
        for (List<FindingTag> findingTags : tagsByFindingId.values()) {
            allTags.addAll(findingTags);
        }
        List<String> choices = Tags.filterChoices(allTags);
        String preserved = Tags.preservedSelection(choices, selectedTagName);

        suppressFilterEvents = true;
        tagFilterCombo.setItems(FXCollections.observableArrayList(choices));
        tagFilterCombo.getSelectionModel().select(preserved);
        suppressFilterEvents = false;
        selectedTagName = preserved;
    }

    /** The one place {@link #findingsTable}'s items are set -- a pure, no-I/O re-derivation from
     *  {@link #allRows} and {@link #tagsByFindingId} (plan §4.2). */
    private void applyTagFilter() {
        findingsTable.setItems(FXCollections.observableArrayList(
                TagFilter.apply(allRows, tagsByFindingId, selectedTagName)));
    }

    private void onScansLoaded(List<ScanSummary> scans) {
        this.loadedScans = scans;
        this.zone = ZoneId.systemDefault();
        this.selectableChoices = ScanChoices.selectable(scans, zone);
        hiddenLabel.setText(Notes.hiddenNote(ScanChoices.hiddenCount(scans)));

        if (selectableChoices.isEmpty()) {
            setLoadControlsDisabled(true);
            datePicker.setValue(null);
            scanCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least one completed scan is needed to view findings", false);
            return;
        }

        setLoadControlsDisabled(false);
        ScanChoice newest = selectableChoices.get(0);
        datePicker.setValue(newest.date());
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

    private void renderSelection(NoteRow row) {
        selectedFindingId = row.findingId();
        detailPane.setVisible(true);
        detailPane.setManaged(true);
        detailSplit.setDividerPositions(DETAIL_SPLIT_OPEN);
        AnimationUtils.fadeInUp(detailPane);

        detailLinesBox.getChildren().clear();
        for (String line : Notes.detailLines(row)) {
            Label label = new Label(line);
            label.getStyleClass().add("detail-line");
            detailLinesBox.getChildren().add(label);
        }

        tagField.clear();
        renderTagsList();

        noteField.clear();
        collapseNoteField();
        renderNotesList();
    }

    private void renderTagsList() {
        List<FindingTag> findingTags = tagsByFindingId.getOrDefault(selectedFindingId, List.of());
        tagList.setItems(FXCollections.observableArrayList(findingTags));
        tagCountLabel.setText(Tags.tagCountLabel(findingTags.size()));
    }

    private void renderNotesList() {
        List<FindingNote> findingNotes = notesByFindingId.getOrDefault(selectedFindingId, List.of());
        List<NoteEntry> entries = Notes.entries(findingNotes, zone);
        notesList.setItems(FXCollections.observableArrayList(entries));
        noteCountLabel.setText(Notes.noteCountLabel(entries.size()));
    }

    private void clearDetail() {
        selectedFindingId = -1L;
        detailPane.setVisible(false);
        detailPane.setManaged(false);
        detailSplit.setDividerPositions(DETAIL_SPLIT_COLLAPSED);
        detailLinesBox.getChildren().clear();
        tagList.setItems(FXCollections.observableArrayList());
        tagCountLabel.setText("");
        tagField.clear();
        removeTagButton.setDisable(true);
        notesList.setItems(FXCollections.observableArrayList());
        noteCountLabel.setText("");
        noteField.clear();
        collapseNoteField();
        deleteNoteButton.setDisable(true);
    }

    private void expandNoteField() {
        noteFieldExpanded = true;
        if (!noteField.getStyleClass().contains("note-field-expanded")) {
            noteField.getStyleClass().add("note-field-expanded");
        }
        AnimationUtils.expandField(noteField, EXPANDED_HEIGHT);
    }

    private void collapseNoteField() {
        noteFieldExpanded = false;
        noteField.getStyleClass().remove("note-field-expanded");
        AnimationUtils.expandField(noteField, COLLAPSED_HEIGHT);
    }

    private void setLoadControlsDisabled(boolean disabled) {
        loadButton.setDisable(disabled);
        datePicker.setDisable(disabled);
        scanCombo.setDisable(disabled);
    }

    private void setNoteControlsDisabled(boolean disabled) {
        addNoteButton.setDisable(disabled);
        deleteNoteButton.setDisable(disabled);
    }

    private void setTagControlsDisabled(boolean disabled) {
        addTagButton.setDisable(disabled);
        removeTagButton.setDisable(disabled);
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

    private static Map<Long, List<FindingNote>> withFindingNotes(
            Map<Long, List<FindingNote>> current, long findingId, List<FindingNote> updated) {
        Map<Long, List<FindingNote>> merged = new LinkedHashMap<>(current);
        merged.put(findingId, List.copyOf(updated));
        return Map.copyOf(merged);
    }

    private static Map<Long, List<FindingTag>> withFindingTags(
            Map<Long, List<FindingTag>> current, long findingId, List<FindingTag> updated) {
        Map<Long, List<FindingTag>> merged = new LinkedHashMap<>(current);
        merged.put(findingId, List.copyOf(updated));
        return Map.copyOf(merged);
    }
}
