package com.argus.ui;

import com.argus.core.EmailEndpoint;
import com.argus.core.Vault;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.function.Supplier;

/** Where the SMTP config lives and what a valid one looks like. Toolkit-free. The {@code
 *  WebhookSettings} twin (P3-17). */
final class EmailSettings {

    /** Vault entry name. Deliberately OUTSIDE {@code ApiKeyNames.PREFIX} ({@code "apikey."}),
     *  the {@code WebhookSettings.VAULT_ENTRY} precedent. */
    static final String VAULT_ENTRY = "notify.email.config";

    private static final System.Logger LOGGER = System.getLogger(EmailSettings.class.getName());

    private EmailSettings() {
    }

    /** Invariant 8: validate before calling {@code core}. Message is a fixed literal, never
     *  echoes input. */
    static Result check(String raw) {
        try {
            EmailEndpoint.parse(raw);
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
    static Supplier<Optional<EmailEndpoint>> fromVault(Vault vault) {
        return () -> {
            String raw;
            try {
                raw = vault.get(VAULT_ENTRY).orElse(null);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.WARNING, "email endpoint unavailable: the vault is closed");
                return Optional.empty();
            }
            if (raw == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(EmailEndpoint.parse(raw));
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING,
                        "email endpoint unavailable: the stored value is not valid");
                return Optional.empty();
            }
        };
    }
}
