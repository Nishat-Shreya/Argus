package com.argus.ui;

import com.argus.core.Vault;
import com.argus.core.WebhookEndpoint;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.function.Supplier;

/** Where the webhook URL lives and what a valid one looks like. Toolkit-free. */
final class WebhookSettings {

    /** Vault entry name. Deliberately OUTSIDE {@code ApiKeyNames.PREFIX} ({@code "apikey."})
     *  so it never shows up in P1-07's provider table. */
    static final String VAULT_ENTRY = "notify.webhook.url";

    private static final System.Logger LOGGER = System.getLogger(WebhookSettings.class.getName());

    private WebhookSettings() {
    }

    /** Invariant 8: validate before calling {@code core}. Message is a fixed literal, never
     *  echoes input. */
    static Result check(String raw) {
        try {
            WebhookEndpoint.parse(raw);
            return new Result(true, null);
        } catch (IllegalArgumentException e) {
            return new Result(false, e.getMessage());
        }
    }

    record Result(boolean valid, String message) {
    }

    /**
     * Production endpoint supplier: reads the vault entry and parses it. NEVER throws — a
     * closed vault, a missing entry or a corrupt value all yield {@code Optional.empty()} plus
     * one log line that does not contain the value.
     */
    static Supplier<Optional<WebhookEndpoint>> fromVault(Vault vault) {
        return () -> {
            String raw;
            try {
                raw = vault.get(VAULT_ENTRY).orElse(null);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING, "webhook endpoint unavailable: the vault is closed");
                return Optional.empty();
            }
            if (raw == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(WebhookEndpoint.parse(raw));
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING,
                        "webhook endpoint unavailable: the stored value is not a valid URL");
                return Optional.empty();
            }
        };
    }
}
