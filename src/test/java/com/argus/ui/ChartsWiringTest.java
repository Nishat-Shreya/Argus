package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanHistory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Section 7.4 of the plan: reflective wiring guard for the charts view -- no toolkit start,
 * never calls {@code Application.launch}, {@code new App()} or {@code Platform.startup} (the
 * {@code ScanDiffWiringTest} shape).
 */
class ChartsWiringTest {

    @Test
    void chartsControllerDeclaresSetHistorySetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory = ChartsController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setOnClose = ChartsController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = ChartsController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void chartsControllerDeclaresFxmlHandlers() {
        Method onShow = findMethod(ChartsController.class, "onShow");
        assertNotNull(onShow, "ChartsController must declare @FXML onShow");
        assertNotNull(onShow.getAnnotation(javafx.fxml.FXML.class));

        Method onBack = findMethod(ChartsController.class, "onBack");
        assertNotNull(onBack, "ChartsController must declare @FXML onBack");
        assertNotNull(onBack.getAnnotation(javafx.fxml.FXML.class));
    }

    @Test
    void dashboardControllerDeclaresSetOpenChartsHandlerAndOnOpenChartsAndKeepsFreeze()
            throws NoSuchMethodException {
        Method setHandler =
                DashboardController.class.getMethod("setOpenChartsHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpenCharts = findMethod(DashboardController.class, "onOpenCharts");
        assertNotNull(onOpenCharts, "DashboardController must declare @FXML onOpenCharts");
        assertNotNull(onOpenCharts.getAnnotation(javafx.fxml.FXML.class));

        // the freeze guard -- every P1-06/P2-09 member must still exist
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenDiffHandler", Runnable.class));
        assertDoesNotThrow(() -> DashboardController.class.getMethod("shutdown"));
    }

    @Test
    void appDeclaresAChartsControllerFieldAndStillDeclaresStop() {
        assertNotNull(findField(App.class, ChartsController.class));
        assertDoesNotThrow(() -> App.class.getMethod("stop"));
    }

    @Test
    void chartsControllerDeclaresNoFieldWhoseTypeIsInComArgusDb() {
        for (Field field : ChartsController.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("com.argus.db"),
                    "ChartsController must declare no field of a com.argus.db type, found: "
                            + field);
        }
    }

    private static Field findField(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
