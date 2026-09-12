package com.argus.ui;

import com.argus.core.ApiKeyNames;

/**
 * The threat-intel providers the key panel manages. Toolkit-free (no javafx.* import).
 *
 * sourceName() MUST equal the IntelSource.name() of the corresponding P2-02..P2-05 class —
 * see plan R4 and the backlog carry-forward. Nothing can cross-check that until those classes
 * exist, so each of P2-02..P2-05 asserts it on arrival.
 */
enum ApiKeySource {
    VIRUSTOTAL("virustotal", "VirusTotal", "sent as the x-apikey request header"),
    SHODAN("shodan", "Shodan", "sent as the key query parameter"),
    ABUSEIPDB("abuseipdb", "AbuseIPDB", "sent as the Key request header"),
    CENSYS("censys", "Censys", "sent as an Authorization: Bearer token");

    private final String sourceName;
    private final String displayName;
    private final String authHint;

    ApiKeySource(String sourceName, String displayName, String authHint) {
        this.sourceName = sourceName;
        this.displayName = displayName;
        this.authHint = authHint;
    }

    /** IntelSource.name() — stable lowercase identifier. */
    String sourceName() {
        return sourceName;
    }

    /** Operator-facing title. */
    String displayName() {
        return displayName;
    }

    /** One line of operator context; never a key, never a URL. */
    String authHint() {
        return authHint;
    }

    /** ApiKeyNames.forSource(sourceName()) — the vault entry this provider's key lives under. */
    String entryName() {
        return ApiKeyNames.forSource(sourceName);
    }
}
