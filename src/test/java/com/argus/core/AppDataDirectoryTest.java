package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Section 6.5: {@code AppDataDirectory} resolution, driven entirely through the injectable
 * {@code resolve(Map, Properties)} seam. Never touches the real environment or system
 * properties (global mutable state across a serial surefire run is exactly the flake P0-01
 * kept the build serial to avoid).
 */
class AppDataDirectoryTest {

    private static final String REAL_USER_HOME = System.getProperty("user.home");

    @Test
    void windowsUsesAppData() {
        Map<String, String> env = Map.of("APPDATA", REAL_USER_HOME + "\\AppData\\Roaming");
        Properties props = propsFor("Windows 11", REAL_USER_HOME);

        Path resolved = AppDataDirectory.resolve(env, props);

        assertEquals(Path.of(REAL_USER_HOME, "AppData", "Roaming", "Argus").toAbsolutePath(),
                resolved);
    }

    @Test
    void windowsWithoutAppDataFallsBackToUserHome() {
        Map<String, String> env = new HashMap<>();
        Properties props = propsFor("Windows 11", REAL_USER_HOME);

        Path resolved = AppDataDirectory.resolve(env, props);

        assertEquals(Path.of(REAL_USER_HOME, "Argus").toAbsolutePath(), resolved);
    }

    @Test
    void linuxUsesXdgDataHomeWhenSet() {
        Map<String, String> env = Map.of("XDG_DATA_HOME", REAL_USER_HOME + "/.xdgdata");
        Properties props = propsFor("Linux", REAL_USER_HOME);

        Path resolved = AppDataDirectory.resolve(env, props);

        assertEquals(Path.of(REAL_USER_HOME, ".xdgdata", "argus").toAbsolutePath(), resolved);
    }

    @Test
    void linuxWithoutXdgUsesLocalShare() {
        Map<String, String> env = new HashMap<>();
        Properties props = propsFor("Linux", REAL_USER_HOME);

        Path resolved = AppDataDirectory.resolve(env, props);

        assertEquals(Path.of(REAL_USER_HOME, ".local", "share", "argus").toAbsolutePath(),
                resolved);
    }

    @Test
    void macUsesApplicationSupport() {
        Map<String, String> env = new HashMap<>();
        Properties props = propsFor("Mac OS X", REAL_USER_HOME);

        Path resolved = AppDataDirectory.resolve(env, props);

        assertEquals(
                Path.of(REAL_USER_HOME, "Library", "Application Support", "Argus")
                        .toAbsolutePath(),
                resolved);
    }

    @Test
    void theOverridePropertyWinsOnEveryPlatform() {
        Path overridePath = Path.of(REAL_USER_HOME, "custom-argus-data").toAbsolutePath();

        for (String osName : new String[] {"Windows 11", "Mac OS X", "Linux"}) {
            Map<String, String> env = Map.of(
                    "APPDATA", REAL_USER_HOME + "\\AppData\\Roaming",
                    "XDG_DATA_HOME", REAL_USER_HOME + "/.xdgdata");
            Properties props = propsFor(osName, REAL_USER_HOME);
            props.setProperty(AppDataDirectory.OVERRIDE_PROPERTY, overridePath.toString());

            Path resolved = AppDataDirectory.resolve(env, props);

            assertEquals(overridePath, resolved, "override must win for os.name=" + osName);
        }
    }

    @Test
    void theResultIsAbsolute() {
        assertTrue(AppDataDirectory
                .resolve(Map.of("APPDATA", REAL_USER_HOME + "\\AppData\\Roaming"),
                        propsFor("Windows 11", REAL_USER_HOME))
                .isAbsolute());
        assertTrue(AppDataDirectory
                .resolve(Map.of(), propsFor("Linux", REAL_USER_HOME))
                .isAbsolute());
        assertTrue(AppDataDirectory
                .resolve(Map.of(), propsFor("Mac OS X", REAL_USER_HOME))
                .isAbsolute());
    }

    private static Properties propsFor(String osName, String userHome) {
        Properties props = new Properties();
        props.setProperty("os.name", osName);
        props.setProperty("user.home", userHome);
        return props;
    }
}
