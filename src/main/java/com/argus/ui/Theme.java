package com.argus.ui;

import java.net.URL;
import javafx.scene.Scene;

/**
 * The single shared Argus stylesheet. Every {@link Scene} in the app — including dialogs
 * and popups, which have their own root and do not inherit the main scene's sheets — goes
 * through here. Never hard-code the stylesheet path at a call site.
 */
public final class Theme {

    /** Classpath location of the shared stylesheet, package-relative to {@code com.argus.ui}. */
    public static final String STYLESHEET = "theme.css";

    private Theme() {
    }

    /**
     * Attaches the Argus stylesheet to {@code scene}, replacing any existing sheets.
     *
     * @throws IllegalStateException if {@code theme.css} is not on the classpath
     */
    public static void applyTo(Scene scene) {
        scene.getStylesheets().setAll(stylesheetUrl());
    }

    /**
     * @return the external form URL of {@code theme.css}
     * @throws IllegalStateException if {@code theme.css} is not on the classpath
     */
    public static String stylesheetUrl() {
        URL url = Theme.class.getResource(STYLESHEET);
        if (url == null) {
            throw new IllegalStateException(
                    "theme.css missing from the classpath; expected "
                            + "src/main/resources/com/argus/ui/theme.css");
        }
        return url.toExternalForm();
    }
}
