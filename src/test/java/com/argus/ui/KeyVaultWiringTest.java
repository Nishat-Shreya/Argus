package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.Vault;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Section 6.9: reflective wiring guard for the key-vault panel -- no toolkit start, never calls
 * {@code Application.launch}, {@code new App()} or {@code Platform.startup} (the
 * {@code AppShutdownContractTest} shape).
 */
class KeyVaultWiringTest {

    @Test
    void keyVaultControllerDeclaresSetVaultSetOnCloseAndRefresh() throws NoSuchMethodException {
        Method setVault = KeyVaultController.class.getMethod("setVault", Vault.class);
        assertTrue(Modifier.isPublic(setVault.getModifiers()));

        Method setOnClose = KeyVaultController.class.getMethod("setOnClose", Runnable.class);
        assertTrue(Modifier.isPublic(setOnClose.getModifiers()));

        Method refresh = KeyVaultController.class.getMethod("refresh");
        assertTrue(Modifier.isPublic(refresh.getModifiers()));
        assertTrue(refresh.getParameterCount() == 0);
    }

    @Test
    void keyVaultControllerDeclaresFxmlHandlers() {
        assertNotNull(findMethod(KeyVaultController.class, "onSaveKey"));
        assertNotNull(findMethod(KeyVaultController.class, "onRemoveKey"));
        assertNotNull(findMethod(KeyVaultController.class, "onBack"));
    }

    @Test
    void dashboardControllerDeclaresSetOpenKeySettingsHandlerAndOnOpenKeysAndNoVaultField()
            throws NoSuchMethodException {
        Method setHandler =
                DashboardController.class.getMethod("setOpenKeySettingsHandler", Runnable.class);
        assertTrue(Modifier.isPublic(setHandler.getModifiers()));

        Method onOpenKeys = findMethod(DashboardController.class, "onOpenKeys");
        assertNotNull(onOpenKeys, "DashboardController must declare @FXML onOpenKeys");
        assertNotNull(onOpenKeys.getAnnotation(javafx.fxml.FXML.class));

        for (Field field : DashboardController.class.getDeclaredFields()) {
            assertFalse(field.getType() == Vault.class,
                    "DashboardController must declare no field of type Vault");
        }
    }

    @Test
    void appDeclaresAKeyVaultControllerFieldAndADashboardControllerField() {
        assertNotNull(findField(App.class, KeyVaultController.class));
        assertNotNull(findField(App.class, DashboardController.class));
    }

    @Test
    void appStillDeclaresStop() throws NoSuchMethodException {
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
