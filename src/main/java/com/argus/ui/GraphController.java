package com.argus.ui;

import com.argus.core.FindingSnapshot;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanHistory;
import com.argus.core.ScanSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

/**
 * Network graph screen. Field wiring, dispatch, and shape construction only (plan §3.9 / §4) --
 * every counting, ordering, grouping, labelling, truncation, colour-mapping and zoom-arithmetic
 * rule lives in {@link GraphModels}, {@link GraphLayout} and {@link GraphViewport}, all directly
 * testable. Historical scope only: one selected completed persisted scan, never the live
 * dashboard (plan §0.1). "Interactive" means exactly four things: scroll to zoom, drag to pan,
 * click a node to select it, hover a node for its label (plan §0.4/§4.4).
 */
public final class GraphController {

    private static final double TARGET_RADIUS = 14;
    private static final double HOST_RADIUS = 10;
    private static final double PORT_RADIUS = 6;
    private static final double LEAF_SIZE = 9;

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
    private Label noteLabel;
    @FXML
    private Label detailLabel;
    @FXML
    private HBox legendBox;
    @FXML
    private Pane canvasPane;
    @FXML
    private Group graphGroup;

    /** Injected by App immediately after the FXML loads. FX-thread-confined. */
    private ScanHistory history;

    /** Injected by App: "put the dashboard root back". FX-thread-confined. */
    private Runnable onClose;

    /** The full, unfiltered load from the last {@link #refresh()}. FX-thread-confined. Retained
     *  (not discarded, unlike {@code ChartsController}) so the root node's label -- the scan's
     *  target -- can be looked up without a new {@code core} query (plan §2). */
    private List<ScanSummary> loadedScans = List.of();

    /** {@code ScanChoices.selectable(loadedScans, ...)}, newest first. FX-thread-confined. */
    private List<ScanChoice> selectableChoices = List.of();

    /** The graph currently rendered, or {@code null} before the first successful render.
     *  FX-thread-confined. */
    private GraphModel model;

    /** {@code GraphLayout.radial(model)}'s output for the current render. FX-thread-confined. */
    private List<GraphPoint> points = List.of();

    /** Pan/zoom state, reset to {@link GraphViewport#identity()} on every new render (plan
     *  §4.4). FX-thread-confined. */
    private GraphViewport viewport = GraphViewport.identity();

    /** The currently selected node, or {@code null}. FX-thread-confined. */
    private GraphNode selected;

    /** The shape for {@link #selected}, so its selection style can be toggled off. FX-thread-
     *  confined. */
    private Node selectedShape;

    /** The last mouse-press point, for computing a drag delta. FX-thread-confined. */
    private double dragAnchorX;
    private double dragAnchorY;

    /**
     * Single-flight guard; a background load or render is in flight. FX-thread-confined, not
     * volatile -- it is read-then-written (a compound op), and confinement to the FX thread is
     * what makes that safe (the {@code KeyVaultController.busy} / {@code ChartsController.busy}
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

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(canvasPane.widthProperty());
        clip.heightProperty().bind(canvasPane.heightProperty());
        canvasPane.setClip(clip);

        canvasPane.setOnScroll(event -> {
            viewport = viewport.zoomedAt(event.getDeltaY() / 40.0, event.getX(), event.getY());
            applyViewport();
            event.consume();
        });

        canvasPane.setOnMousePressed(event -> {
            dragAnchorX = event.getX();
            dragAnchorY = event.getY();
        });

        canvasPane.setOnMouseDragged(event -> {
            double dx = event.getX() - dragAnchorX;
            double dy = event.getY() - dragAnchorY;
            viewport = viewport.pannedBy(dx, dy);
            applyViewport();
            dragAnchorX = event.getX();
            dragAnchorY = event.getY();
        });

        canvasPane.setOnMouseClicked(event -> clearSelection());
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
        noteLabel.setText("");
        hiddenLabel.setText("");
        clearCanvas();
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
        ScanSummary summary = choice == null ? null : summaryFor(choice.scanId());
        if (choice == null || summary == null) {
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
            List<FindingSnapshot> findings = task.getValue();
            render(GraphModels.of(summary.target(), findings));
            if (findings.isEmpty()) {
                showMessage("this scan recorded no findings", false);
            } else {
                clearMessage();
            }
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
        this.loadedScans = scans;
        ZoneId zone = ZoneId.systemDefault();
        this.selectableChoices = ScanChoices.selectable(scans, zone);
        int hidden = ScanChoices.hiddenCount(scans);
        hiddenLabel.setText(hidden == 0 ? ""
                : hidden + " scans hidden — cancelled or failed scans are partial and cannot be "
                        + "graphed");

        if (selectableChoices.isEmpty()) {
            setControlsDisabled(true);
            scanDatePicker.setValue(null);
            scanCombo.setItems(FXCollections.observableArrayList());
            showMessage("at least one completed scan is needed to graph", false);
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

    private void render(GraphModel builtModel) {
        this.model = builtModel;
        this.points = GraphLayout.radial(builtModel);
        this.viewport = GraphViewport.identity();
        this.selected = null;
        this.selectedShape = null;
        detailLabel.setText(null);

        draw();
        applyViewport();
        renderLegend();
        noteLabel.setText(noteLine(builtModel));
    }

    /** The leaf-truncation note (if the scan was capped) plus a count of node labels hidden on
     *  crowded rings (plan §3.6: "the controller reports the count in the note line") -- hidden
     *  labels are never lost information, since hover/click still reveal them (plan §0.4). */
    private String noteLine(GraphModel builtModel) {
        String truncation = builtModel.truncationNote();
        long hiddenLabels = points.stream().filter(point -> !point.labelled()).count();

        StringBuilder note = new StringBuilder();
        if (!truncation.isEmpty()) {
            note.append(truncation);
        }
        if (hiddenLabels > 0) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append(hiddenLabels).append(" node label")
                    .append(hiddenLabels == 1 ? "" : "s")
                    .append(" hidden on crowded rings — hover or click a node to see its name");
        }
        return note.toString();
    }

    private void draw() {
        graphGroup.getChildren().clear();

        Map<String, GraphPoint> pointById = new HashMap<>();
        for (GraphPoint point : points) {
            pointById.put(point.nodeId(), point);
        }

        double width = Math.max(canvasPane.getWidth(), 1.0);
        double height = Math.max(canvasPane.getHeight(), 1.0);

        // pass 1: every edge
        for (GraphNode node : model.nodes()) {
            if (node.parentId() == null) {
                continue;
            }
            GraphPoint child = pointById.get(node.id());
            GraphPoint parent = pointById.get(node.parentId());
            Line line = new Line(parent.x() * width, parent.y() * height,
                    child.x() * width, child.y() * height);
            line.getStyleClass().add("graph-edge");
            graphGroup.getChildren().add(line);
        }

        // pass 2: every node shape
        for (GraphNode node : model.nodes()) {
            GraphPoint point = pointById.get(node.id());
            double x = point.x() * width;
            double y = point.y() * height;
            Shape shape = shapeFor(node.kind(), x, y);
            shape.getStyleClass().addAll(GraphLayout.styleClassesFor(node));
            shape.setUserData(node);
            Tooltip.install(shape, new Tooltip(node.label()));
            shape.setOnMouseClicked(event -> {
                selectNode(node, shape);
                event.consume();
            });
            graphGroup.getChildren().add(shape);
        }

        // pass 3: every label
        for (GraphNode node : model.nodes()) {
            GraphPoint point = pointById.get(node.id());
            if (!point.labelled()) {
                continue;
            }
            Text text = new Text(point.x() * width + 8, point.y() * height + 4, node.label());
            text.getStyleClass().add("graph-label");
            graphGroup.getChildren().add(text);
        }
    }

    private static Shape shapeFor(GraphNodeKind kind, double x, double y) {
        return switch (kind) {
            case TARGET -> new Circle(x, y, TARGET_RADIUS);
            case HOST -> new Circle(x, y, HOST_RADIUS);
            case PORT -> new Circle(x, y, PORT_RADIUS);
            case LEAF -> new Rectangle(x - LEAF_SIZE / 2, y - LEAF_SIZE / 2, LEAF_SIZE, LEAF_SIZE);
        };
    }

    private void renderLegend() {
        legendBox.getChildren().clear();
        for (GraphLegendEntry entry : GraphLayout.legend(model)) {
            HBox item = new HBox(4);
            Circle swatch = new Circle(5);
            swatch.getStyleClass().addAll("graph-legend-swatch", entry.styleClass());
            Label label = new Label(entry.label());
            item.getChildren().addAll(swatch, label);
            legendBox.getChildren().add(item);
        }
    }

    private void selectNode(GraphNode node, Node shape) {
        if (selectedShape != null) {
            selectedShape.getStyleClass().remove("graph-node-selected");
        }
        selected = node;
        selectedShape = shape;
        shape.getStyleClass().add("graph-node-selected");
        detailLabel.setText(GraphModels.describe(model, node));
    }

    private void clearSelection() {
        if (selectedShape != null) {
            selectedShape.getStyleClass().remove("graph-node-selected");
        }
        selected = null;
        selectedShape = null;
        detailLabel.setText(null);
    }

    private void applyViewport() {
        graphGroup.setScaleX(viewport.scale());
        graphGroup.setScaleY(viewport.scale());
        graphGroup.setTranslateX(viewport.translateX());
        graphGroup.setTranslateY(viewport.translateY());
    }

    private void clearCanvas() {
        graphGroup.getChildren().clear();
        legendBox.getChildren().clear();
        model = null;
        points = List.of();
        viewport = GraphViewport.identity();
        selected = null;
        selectedShape = null;
        detailLabel.setText(null);
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
