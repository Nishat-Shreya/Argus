package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Section 6.13: reflection guard that {@code App} holds a {@code DashboardController} and that
 * {@code DashboardController} exposes {@code shutdown()}. The {@code LauncherContractTest}
 * shape -- never calls {@code Application.launch}, {@code new App()} or {@code
 * Platform.startup}.
 */
class AppShutdownContractTest {

    @Test
    void appHoldsADashboardController() {
        Field field = findField(App.class, DashboardController.class);
        assertNotNull(field, "App must declare a field of type DashboardController");
    }

    @Test
    void dashboardControllerExposesShutdown() throws NoSuchMethodException {
        Method shutdown = DashboardController.class.getMethod("shutdown");
        assertTrue(Modifier.isPublic(shutdown.getModifiers()), "shutdown must be public");
        assertDoesNotThrow(() -> DashboardController.class.getMethod("shutdown"));
        assertTrue(shutdown.getParameterCount() == 0, "shutdown must take no arguments");
    }

    @Test
    void appStillDeclaresStop() throws NoSuchMethodException {
        Method stop = App.class.getMethod("stop");
        assertTrue(Modifier.isPublic(stop.getModifiers()), "stop must still be public");
    }

    private static Field findField(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }
}
