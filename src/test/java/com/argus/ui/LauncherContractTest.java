package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import javafx.application.Application;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code Launcher} / {@code App} split in place by reflection only. Never calls
 * {@code Launcher.main}, {@code new App()}, {@code Application.launch} or
 * {@code Platform.startup} — any of those would try to start the JavaFX toolkit and hang
 * or fail the headless build.
 */
class LauncherContractTest {

    @Test
    void launcherDoesNotExtendApplication() {
        assertFalse(Application.class.isAssignableFrom(Launcher.class),
                "Launcher must NOT extend Application (classpath launch shim)");
    }

    @Test
    void appExtendsApplication() {
        assertTrue(Application.class.isAssignableFrom(App.class),
                "App must be the real Application subclass");
    }

    @Test
    void launcherExposesTheMainMethodThePomPointsAt() throws NoSuchMethodException {
        Method main = Launcher.class.getMethod("main", String[].class);
        assertTrue(Modifier.isPublic(main.getModifiers()), "main must be public");
        assertTrue(Modifier.isStatic(main.getModifiers()), "main must be static");
        assertTrue(main.getReturnType() == void.class, "main must return void");
    }
}
