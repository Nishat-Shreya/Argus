package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ApiKeyNames;
import com.argus.core.OperatorId;
import com.argus.core.Vault;
import com.argus.core.VaultException;
import com.argus.core.VaultStore;
import com.argus.core.WebhookEndpoint;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 6.8 of the P3-03 plan: {@link WebhookSettings}. Uses a REAL {@link Vault} on a
 * {@code @TempDir} (P1-04/P1-05's "never in-memory" rule).
 */
class WebhookSettingsTest {

    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @Test
    void g1CheckAcceptsAValidHttpsUrl() {
        WebhookSettings.Result result =
                WebhookSettings.check("https://hooks.example.com/services/T1/B2/abc");
        assertTrue(result.valid());
    }

    @Test
    void g2CheckRejectsBlankMalformedPlainHttpAndOverLong() {
        assertRejected(WebhookSettings.check(""));
        assertRejected(WebhookSettings.check("   "));
        assertRejected(WebhookSettings.check(null));
        assertRejected(WebhookSettings.check("not a url"));
        assertRejected(WebhookSettings.check("http://hooks.example.com/x"));
        assertRejected(WebhookSettings.check("https://hooks.example.com/" + "a".repeat(2048)));
    }

    @Test
    void g3FromVaultReturnsTheParsedEndpointWhenPresent(@TempDir Path tempDir)
            throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(WebhookSettings.VAULT_ENTRY, "https://hooks.example.com/x");
            Supplier<Optional<WebhookEndpoint>> supplier = WebhookSettings.fromVault(vault);
            Optional<WebhookEndpoint> endpoint = supplier.get();
            assertTrue(endpoint.isPresent());
            assertEquals(WebhookEndpoint.parse("https://hooks.example.com/x"), endpoint.get());
        }
    }

    @Test
    void g4EntryAbsentYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            Supplier<Optional<WebhookEndpoint>> supplier = WebhookSettings.fromVault(vault);
            assertEquals(Optional.empty(), supplier.get());
        }
    }

    @Test
    void g5CorruptEntryYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(WebhookSettings.VAULT_ENTRY, "not a url");
            Supplier<Optional<WebhookEndpoint>> supplier = WebhookSettings.fromVault(vault);
            assertEquals(Optional.empty(), supplier.get());
        }
    }

    @Test
    void g6ClosedVaultYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        Vault vault = createVault(tempDir);
        vault.put(WebhookSettings.VAULT_ENTRY, "https://hooks.example.com/x");
        vault.close();

        Supplier<Optional<WebhookEndpoint>> supplier = WebhookSettings.fromVault(vault);
        assertEquals(Optional.empty(), supplier.get());
    }

    @Test
    void g7VaultEntryDoesNotStartWithApiKeyPrefix() {
        assertFalse(WebhookSettings.VAULT_ENTRY.startsWith(ApiKeyNames.PREFIX));
    }

    @Test
    void g8RoundTrip(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(WebhookSettings.VAULT_ENTRY, "https://hooks.example.com/round-trip");
            Optional<WebhookEndpoint> endpoint = WebhookSettings.fromVault(vault).get();
            assertEquals("https://hooks.example.com/round-trip", endpoint.orElseThrow().uri().toString());
        }
    }

    private static void assertRejected(WebhookSettings.Result result) {
        assertFalse(result.valid());
        assertTrue(result.message() != null && !result.message().isBlank());
    }

    private static Vault createVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.at(tempDir);
        OperatorId operator = OperatorId.of("nishat");
        return store.create(operator, PASSWORD.clone());
    }
}
