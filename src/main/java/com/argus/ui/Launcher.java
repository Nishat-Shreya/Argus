package com.argus.ui;

import javafx.application.Application;

/**
 * Entry point. Deliberately does <strong>not</strong> extend {@link Application}: on the
 * classpath, a main class that is itself an {@code Application} subclass trips the JVM
 * launcher's "JavaFX runtime components are missing" check. A plain class sidesteps it and
 * makes {@code javafx:run}, IDE "Run", and {@code java -cp} all work. Locked in P0-01; the
 * POM's {@code javafx-maven-plugin} {@code <mainClass>} already points here.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
