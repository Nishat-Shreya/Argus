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
 * Section 7.5 of the plan: reflective wiring guard for the timeline view -- no toolkit start,
 * never calls {@code Application.launch}, {@code new App()} or {@code Platform.startup} (the
 * {@code GraphWiringTest} shape).
 */
class TimelineWiringTest {

    @Test
    void timelineControllerDeclaresSetHistorySetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory = TimelineController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setOnClose = TimelineController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = TimelineController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void timelineControllerDeclaresNoFieldWhoseTypeIsInComArgusDb() {
        for (Field field : TimelineController.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("com.argus.db"),
                    "TimelineController must declare no field of a com.argus.db type, found: "
                            + field);
        }
    }

    @Test
    void timelineControllerDeclaresANonVolatileBusyField() throws NoSuchFieldException {
        Field busy = TimelineController.class.getDeclaredField("busy");
        assertFalse(Modifier.isVolatile(busy.getModifiers()),
                "busy must NOT be volatile -- it is read-then-written (a compound op), and "
                        + "confinement to the FX thread is what makes that safe (plan §5)");
    }

    @Test
    void timelineControllerDeclaresANonVolatileRenderedIndexField() throws NoSuchFieldException {
        Field renderedIndex = TimelineController.class.getDeclaredField("renderedIndex");
        assertFalse(Modifier.isVolatile(renderedIndex.getModifiers()),
                "renderedIndex must NOT be volatile -- it is read-then-written from the value "
                        + "listener, and confinement to the FX thread is what makes that safe "
                        + "(plan §5)");
    }

    @Test
    void dashboardControllerDeclaresSetOpenTimelineHandlerAndOnOpenTimelineAndKeepsFreeze()
            throws NoSuchMethodException {
        Method setHandler =
                DashboardController.class.getMethod("setOpenTimelineHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpenTimeline = findMethod(DashboardController.class, "onOpenTimeline");
        assertNotNull(onOpenTimeline, "DashboardController must declare @FXML onOpenTimeline");
        assertNotNull(onOpenTimeline.getAnnotation(javafx.fxml.FXML.class));

        // the freeze guard -- every prior member must still exist
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenGraphHandler", Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenChartsHandler", Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenDiffHandler", Runnable.class));
        assertDoesNotThrow(() ->
                DashboardController.class.getMethod("setOpenKeySettingsHandler", Runnable.class));
        assertDoesNotThrow(() -> DashboardController.class.getMethod("shutdown"));
    }

    @Test
    void appDeclaresAPrivateShowTimelineMethodAndATimelineControllerFieldAndStillDeclaresStop()
            throws NoSuchMethodException {
        assertNotNull(findField(App.class, TimelineController.class));

        Method showTimeline = App.class.getDeclaredMethod("showTimeline", javafx.scene.Scene.class);
        assertTrue(Modifier.isPrivate(showTimeline.getModifiers()),
                "showTimeline must be private -- nothing outside App routes navigation");

        assertDoesNotThrow(() -> App.class.getMethod("stop"));
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
