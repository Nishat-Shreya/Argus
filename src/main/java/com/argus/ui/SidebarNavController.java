package com.argus.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

/**
 * The reusable sidebar navigation component (Part 1 of the UI redesign), embedded via {@code
 * <fx:include fx:id="sidebar">} on each screen that adopts it. Every nav item reuses the EXACT
 * same {@code Runnable}-handler injection pattern {@code App}/{@code DashboardController}
 * already use for every existing "open X screen" action (e.g. {@code
 * setOpenDiffHandler(Runnable)}) -- this class only presents that navigation, it does not add
 * any new navigation target or change what an existing one does.
 *
 * NOT YET wired into every screen: for Part 1, only the dashboard embeds this. Other screens
 * keep their existing per-screen toolbar until their own redesign batch adopts the sidebar too.
 */
public final class SidebarNavController {

    @FXML
    private HBox brandBox;
    @FXML
    private HBox dashboardItem;
    @FXML
    private HBox findingsItem;
    @FXML
    private HBox diffItem;
    @FXML
    private HBox chartsItem;
    @FXML
    private HBox graphItem;
    @FXML
    private HBox timelineItem;
    @FXML
    private HBox scheduledScansItem;
    @FXML
    private HBox reportItem;
    @FXML
    private HBox notificationsItem;
    @FXML
    private HBox apiKeysItem;

    private final Map<String, HBox> itemsByKey = new LinkedHashMap<>();
    private final Map<String, Runnable> handlersByKey = new LinkedHashMap<>();

    @FXML
    private void initialize() {
        brandBox.getChildren().addAll(Icons.eye(1.0), brandLabels());

        itemsByKey.put("dashboard", dashboardItem);
        itemsByKey.put("findings", findingsItem);
        itemsByKey.put("diff", diffItem);
        itemsByKey.put("charts", chartsItem);
        itemsByKey.put("graph", graphItem);
        itemsByKey.put("timeline", timelineItem);
        itemsByKey.put("scheduled-scans", scheduledScansItem);
        itemsByKey.put("reports", reportItem);
        itemsByKey.put("notifications", notificationsItem);
        itemsByKey.put("api-keys", apiKeysItem);

        for (Map.Entry<String, HBox> entry : itemsByKey.entrySet()) {
            entry.getValue().getChildren().add(0, Icons.forName(iconNameFor(entry.getKey())));
        }
    }

    /** Highlights exactly the item for {@code key}; every other item returns to its plain
     *  state. Unknown keys just clear every highlight (the dashboard nav item's own key is
     *  {@code "dashboard"}). */
    void setActive(String key) {
        for (Map.Entry<String, HBox> entry : itemsByKey.entrySet()) {
            ObservableList<String> styleClasses = entry.getValue().getStyleClass();
            styleClasses.remove("sidebar-nav-item-active");
            if (entry.getKey().equals(key)) {
                styleClasses.add("sidebar-nav-item-active");
            }
        }
    }

    void setOnDashboard(Runnable handler) {
        handlersByKey.put("dashboard", handler);
    }

    void setOnFindings(Runnable handler) {
        handlersByKey.put("findings", handler);
    }

    void setOnDiff(Runnable handler) {
        handlersByKey.put("diff", handler);
    }

    void setOnCharts(Runnable handler) {
        handlersByKey.put("charts", handler);
    }

    void setOnGraph(Runnable handler) {
        handlersByKey.put("graph", handler);
    }

    void setOnTimeline(Runnable handler) {
        handlersByKey.put("timeline", handler);
    }

    void setOnScheduledScans(Runnable handler) {
        handlersByKey.put("scheduled-scans", handler);
    }

    void setOnReport(Runnable handler) {
        handlersByKey.put("reports", handler);
    }

    void setOnNotifications(Runnable handler) {
        handlersByKey.put("notifications", handler);
    }

    void setOnApiKeys(Runnable handler) {
        handlersByKey.put("api-keys", handler);
    }

    @FXML
    private void onDashboard(MouseEvent event) {
        dispatch("dashboard");
    }

    @FXML
    private void onFindings(MouseEvent event) {
        dispatch("findings");
    }

    @FXML
    private void onDiff(MouseEvent event) {
        dispatch("diff");
    }

    @FXML
    private void onCharts(MouseEvent event) {
        dispatch("charts");
    }

    @FXML
    private void onGraph(MouseEvent event) {
        dispatch("graph");
    }

    @FXML
    private void onTimeline(MouseEvent event) {
        dispatch("timeline");
    }

    @FXML
    private void onScheduledScans(MouseEvent event) {
        dispatch("scheduled-scans");
    }

    @FXML
    private void onReport(MouseEvent event) {
        dispatch("reports");
    }

    @FXML
    private void onNotifications(MouseEvent event) {
        dispatch("notifications");
    }

    @FXML
    private void onApiKeys(MouseEvent event) {
        dispatch("api-keys");
    }

    private void dispatch(String key) {
        Runnable handler = handlersByKey.get(key);
        if (handler != null) {
            handler.run();
        }
    }

    private static String iconNameFor(String key) {
        return switch (key) {
            case "dashboard" -> "dashboard";
            case "findings" -> "findings";
            case "diff" -> "diff";
            case "charts" -> "charts";
            case "graph" -> "graph";
            case "timeline" -> "scheduled-scans";
            case "scheduled-scans" -> "scheduled-scans";
            case "reports" -> "reports";
            case "notifications" -> "notifications";
            case "api-keys" -> "settings";
            default -> "settings";
        };
    }

    private static javafx.scene.layout.VBox brandLabels() {
        Label name = new Label("ARGUS");
        name.getStyleClass().add("sidebar-brand-name");
        Label tagline = new Label("See More. Stop More.");
        tagline.getStyleClass().add("sidebar-brand-tagline");
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(name, tagline);
        box.setSpacing(1);
        return box;
    }

    /** Test-support only. */
    static ObservableList<String> activeStyleClassesForTest(HBox item) {
        return FXCollections.unmodifiableObservableList(item.getStyleClass());
    }
}
