package com.argus.core;

import java.util.regex.Pattern;

/**
 * The vault entry name an IntelSource's API key is stored under: "apikey." + sourceName.
 * The single source of truth for that convention (the DomainName / OperatorId precedent) —
 * com.argus.ui writes the entry, P2-02..P2-05 read it, and neither restates the rule.
 *
 * Never logs, never appears in a message containing key material: an ENTRY NAME is not a
 * secret (MissingApiKeyException already prints one; Vault.keys() is public API).
 */
public final class ApiKeyNames {

    /** Namespace prefix. Every entry this project's UI manages starts with it. */
    public static final String PREFIX = "apikey.";

    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9]+");

    private ApiKeyNames() {
    }

    /**
     * @param sourceName an IntelSource.name() — lowercase [a-z0-9]+, never a display title
     * @throws IllegalArgumentException if sourceName is null, blank, not [a-z0-9]+, or already
     *                                   carries {@link #PREFIX}
     */
    public static String forSource(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("source name must not be null or blank");
        }
        if (!SOURCE_NAME.matcher(sourceName).matches()) {
            throw new IllegalArgumentException(
                    "source name must be lowercase letters and digits only: " + sourceName);
        }
        if (sourceName.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "source name must not already carry the '" + PREFIX + "' prefix: "
                            + sourceName);
        }
        return PREFIX + sourceName;
    }
}
