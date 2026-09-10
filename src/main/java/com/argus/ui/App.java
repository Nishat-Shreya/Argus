package com.argus.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * The JavaFX lifecycle for Argus. Opens one dark, themed, empty window. {@link #start} runs
 * on the FX Application Thread and stays synchronous and trivial (invariant 3): the only
 * I/O is the sub-millisecond classpath lookup for the stylesheet.
 */
public final class App extends Application {

    public static final String WINDOW_TITLE = "Argus";

    @Override
    public void start(Stage stage) {
        Label bootLine = new Label("argus — no target loaded");
        bootLine.getStyleClass().add("boot-line");

        StackPane root = new StackPane(bootLine);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 1280, 800);
        Theme.applyTo(scene);

        AnimationUtils.fadeInUp(bootLine);

        stage.setTitle(WINDOW_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * JavaFX shutdown hook. Nothing to release yet — this is where invariant 6's executor
     * shutdown ({@code shutdown()} &rarr; {@code awaitTermination()} &rarr;
     * {@code shutdownNow()}) will live once P1-06 owns a scan pool. Not a window close
     * handler, not a JVM shutdown hook.
     */
    @Override
    public void stop() {
        // no-op: no threads or pools exist yet.
    }
}
