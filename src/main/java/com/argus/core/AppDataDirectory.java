package com.argus.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Where the vault lives (plan §3.6/§7.5): the per-user application data directory, one level
 * above the {@code vaults} subfolder that {@link VaultStore#atDefaultLocation()} resolves into.
 */
final class AppDataDirectory {

    static final String OVERRIDE_PROPERTY = "argus.dataDir";

    private AppDataDirectory() {
    }

    /** Production: delegates to the seam below with {@code System.getenv()} / system properties. */
    static Path resolve() {
        return resolve(System.getenv(), System.getProperties());
    }

    /**
     * Pure, injectable seam — the P1-01 {@code SocketConnector} / P1-02 {@code HttpFetcher}
     * pattern, so the per-OS branches are unit-testable without touching the real environment.
     */
    static Path resolve(Map<String, String> env, Properties props) {
        String override = props.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }

        String osName = props.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = props.getProperty("user.home", "");

        Path base;
        if (osName.contains("win")) {
            String appData = env.get("APPDATA");
            Path root = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(userHome);
            base = root.resolve("Argus");
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            base = Path.of(userHome, "Library", "Application Support", "Argus");
        } else {
            String xdgDataHome = env.get("XDG_DATA_HOME");
            base = (xdgDataHome != null && !xdgDataHome.isBlank())
                    ? Path.of(xdgDataHome, "argus")
                    : Path.of(userHome, ".local", "share", "argus");
        }
        return base.toAbsolutePath();
    }
}
