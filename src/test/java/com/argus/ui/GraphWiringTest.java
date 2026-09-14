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
 * Section 7.5 of the plan: reflective wiring guard for the graph view -- no toolkit start, never
 * calls {@code Application.launch}, {@code new App()} or {@code Platform.startup} (the
 * {@code ChartsWiringTest} shape).
 */
class GraphWiringTest {

    @Test
    void graphControllerDeclaresSetHistorySetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory = GraphController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setOnClose = GraphController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = GraphController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void dashboardControllerDeclaresSetOpenGraphHandlerAndOnOpenGraphAndKeepsFreeze()
            throws NoSuchMethodException {
        Method setHandler =
                DashboardController.class.getMethod("setOpenGraphHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpenGraph = findMethod(DashboardController.class, "onOpenGraph");
        assertNotNull(onOpenGraph, "DashboardController must declare @FXML onOpenGraph");
        assertNotNull(onOpenGraph.getAnnotation(javafx.fxml.FXML.class));

        // the freeze guard -- every prior member must still exist
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenChartsHandler", Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenDiffHandler", Runnable.class));
        assertDoesNotThrow(() -> DashboardController.class.getMethod("shutdown"));
    }

    @Test
    void appDeclaresAPrivateShowGraphMethodAndAGraphControllerFieldAndStillDeclaresStop()
            throws NoSuchMethodException {
        assertNotNull(findField(App.class, GraphController.class));

        Method showGraph = App.class.getDeclaredMethod("showGraph", javafx.scene.Scene.class);
        assertTrue(Modifier.isPrivate(showGraph.getModifiers()),
                "showGraph must be private -- nothing outside App routes navigation");

        assertDoesNotThrow(() -> App.class.getMethod("stop"));
    }

    @Test
    void graphControllerDeclaresNoFieldWhoseTypeIsInComArgusDb() {
        for (Field field : GraphController.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("com.argus.db"),
                    "GraphController must declare no field of a com.argus.db type, found: "
                            + field);
        }
    }

    @Test
    void graphControllerDeclaresANonVolatileBusyField() throws NoSuchFieldException {
        Field busy = GraphController.class.getDeclaredField("busy");
        assertFalse(Modifier.isVolatile(busy.getModifiers()),
                "busy must NOT be volatile -- it is read-then-written (a compound op), and "
                        + "confinement to the FX thread is what makes that safe (plan §5)");
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
