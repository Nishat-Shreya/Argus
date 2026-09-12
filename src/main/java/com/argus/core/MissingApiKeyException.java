package com.argus.core;

/**
 * No API key for this source in the unlocked vault. Separate from every other failure because
 * it is the one the operator can fix, and because ThreatIntelClient (P2-06) skips an
 * unconfigured source rather than reporting it as an outage.
 *
 * The message names the VAULT ENTRY NAME (not a secret — Vault.keys() is already public API)
 * and never the value.
 */
public final class MissingApiKeyException extends IntelSourceException {

    private final String vaultEntryName;

    public MissingApiKeyException(String sourceName, String vaultEntryName) {
        super(sourceName, "no API key configured for " + sourceName
                + " (missing vault entry: " + vaultEntryName + ")");
        this.vaultEntryName = vaultEntryName;
    }

    public String vaultEntryName() {
        return vaultEntryName;
    }
}
