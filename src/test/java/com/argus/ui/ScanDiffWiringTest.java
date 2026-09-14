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
 * Section 7.10: reflective wiring guard for the scan diff view -- no toolkit start, never calls
 * {@code Application.launch}, {@code new App()} or {@code Platform.startup} (the
 * {@code KeyVaultWiringTest} shape).
 */
class ScanDiffWiringTest {

    @Test
    void scanDiffControllerDeclaresSetHistorySetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setHistory = ScanDiffController.class.getMethod("setHistory", ScanHistory.class);
        assertTrue(Modifier.isPublic(setHistory.getModifiers()));

        Method setOnClose = ScanDiffController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = ScanDiffController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void scanDiffControllerDeclaresFxmlHandlers() {
        Method onCompare = findMethod(ScanDiffController.class, "onCompare");
        assertNotNull(onCompare, "ScanDiffController must declare @FXML onCompare");
        assertNotNull(onCompare.getAnnotation(javafx.fxml.FXML.class));

        Method onBack = findMethod(ScanDiffController.class, "onBack");
        assertNotNull(onBack, "ScanDiffController must declare @FXML onBack");
        assertNotNull(onBack.getAnnotation(javafx.fxml.FXML.class));
    }

    @Test
    void dashboardControllerDeclaresSetOpenDiffHandlerAndOnOpenDiff() throws NoSuchMethodException {
        Method setHandler =
                DashboardController.class.getMethod("setOpenDiffHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpenDiff = findMethod(DashboardController.class, "onOpenDiff");
        assertNotNull(onOpenDiff, "DashboardController must declare @FXML onOpenDiff");
        assertNotNull(onOpenDiff.getAnnotation(javafx.fxml.FXML.class));
    }

    @Test
    void appDeclaresAScanDiffControllerFieldAndStillDeclaresStop() {
        assertNotNull(findField(App.class, ScanDiffController.class));
        assertDoesNotThrow(() -> App.class.getMethod("stop"));
    }

    @Test
    void scanDiffControllerDeclaresNoFieldWhoseTypeIsInComArgusDb() {
        for (Field field : ScanDiffController.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("com.argus.db"),
                    "ScanDiffController must declare no field of a com.argus.db type, found: "
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
