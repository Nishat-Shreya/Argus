package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ApiKeyNames;
import com.argus.core.EmailEndpoint;
import com.argus.core.OperatorId;
import com.argus.core.Vault;
import com.argus.core.VaultException;
import com.argus.core.VaultStore;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link EmailSettings}. Uses a REAL {@link Vault} on a {@code @TempDir}. The {@code
 *  WebhookSettingsTest} twin (P3-17). */
class EmailSettingsTest {

    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private static final String VALID =
            "smtp.example.com|465|operator@example.com|ops@example.com|s3cret";

    @Test
    void g1CheckAcceptsAWellFormedConfig() {
        assertTrue(EmailSettings.check(VALID).valid());
    }

    @Test
    void g2CheckRejectsBlankMalformedAndWrongFieldCount() {
        assertRejected(EmailSettings.check(""));
        assertRejected(EmailSettings.check("   "));
        assertRejected(EmailSettings.check(null));
        assertRejected(EmailSettings.check("not the right shape"));
        assertRejected(EmailSettings.check("smtp.example.com|465|operator@example.com"));
    }

    @Test
    void g3FromVaultReturnsTheParsedEndpointWhenPresent(@TempDir Path tempDir)
            throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(EmailSettings.VAULT_ENTRY, VALID);
            Supplier<Optional<EmailEndpoint>> supplier = EmailSettings.fromVault(vault);
            Optional<EmailEndpoint> endpoint = supplier.get();
            assertTrue(endpoint.isPresent());
            assertEquals(EmailEndpoint.parse(VALID), endpoint.get());
        }
    }

    @Test
    void g4EntryAbsentYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            Supplier<Optional<EmailEndpoint>> supplier = EmailSettings.fromVault(vault);
            assertEquals(Optional.empty(), supplier.get());
        }
    }

    @Test
    void g5CorruptEntryYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(EmailSettings.VAULT_ENTRY, "not the right shape");
            Supplier<Optional<EmailEndpoint>> supplier = EmailSettings.fromVault(vault);
            assertEquals(Optional.empty(), supplier.get());
        }
    }

    @Test
    void g6ClosedVaultYieldsEmpty(@TempDir Path tempDir) throws VaultException {
        Vault vault = createVault(tempDir);
        vault.put(EmailSettings.VAULT_ENTRY, VALID);
        vault.close();

        Supplier<Optional<EmailEndpoint>> supplier = EmailSettings.fromVault(vault);
        assertEquals(Optional.empty(), supplier.get());
    }

    @Test
    void g7VaultEntryDoesNotStartWithApiKeyPrefix() {
        assertFalse(EmailSettings.VAULT_ENTRY.startsWith(ApiKeyNames.PREFIX));
    }

    @Test
    void g8RoundTrip(@TempDir Path tempDir) throws VaultException {
        try (Vault vault = createVault(tempDir)) {
            vault.put(EmailSettings.VAULT_ENTRY, VALID);
            Optional<EmailEndpoint> endpoint = EmailSettings.fromVault(vault).get();
            assertEquals("ops@example.com", endpoint.orElseThrow().recipient());
        }
    }

    private static void assertRejected(EmailSettings.Result result) {
        assertFalse(result.valid());
        assertTrue(result.message() != null && !result.message().isBlank());
    }

    private static Vault createVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.at(tempDir);
        OperatorId operator = OperatorId.of("nishat");
        return store.create(operator, PASSWORD.clone());
    }
}
