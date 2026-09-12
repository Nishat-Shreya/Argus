package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.OperatorId;
import com.argus.core.Vault;
import com.argus.core.VaultException;
import com.argus.core.VaultStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The backlog's named "add / update / remove round-trip through the vault" test (plan §6.7).
 * One {@code VaultStore.at(@TempDir).create(...)} in {@code @BeforeAll} pays ONE
 * production-strength PBKDF2 derivation for the whole class (plan §8 R7) -- every case below
 * reuses the unlocked handle. {@code VaultStore.forTesting} is NOT widened for this item
 * (R7's explicit instruction).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiKeyVaultRoundTripTest {

    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @TempDir
    static Path tempDir;

    static VaultStore store;
    static OperatorId operator;
    static Vault vault;

    @BeforeAll
    static void createVaultOnce() throws VaultException {
        store = VaultStore.at(tempDir);
        operator = OperatorId.of("nishat");
        vault = store.create(operator, PASSWORD.clone());
    }

    @AfterAll
    static void closeVault() {
        vault.close();
    }

    @Test
    @Order(1)
    void addMarksExactlyThatRowConfigured() throws VaultException {
        vault.put(ApiKeySource.VIRUSTOTAL.entryName(), "k1-first-value");

        List<ApiKeyRow> rows = ApiKeyRows.from(vault.keys());
        for (ApiKeyRow row : rows) {
            assertEquals(row.source() == ApiKeySource.VIRUSTOTAL, row.configured());
        }
    }

    @Test
    @Order(2)
    void updateOverwritesWithoutAddingARow() throws VaultException {
        vault.put(ApiKeySource.VIRUSTOTAL.entryName(), "k2-second-value");

        List<ApiKeyRow> rows = ApiKeyRows.from(vault.keys());
        long configuredCount = rows.stream().filter(ApiKeyRow::configured).count();
        assertEquals(1, configuredCount);
        assertEquals("k2-second-value", vault.get(ApiKeySource.VIRUSTOTAL.entryName()).orElseThrow());
    }

    @Test
    @Order(3)
    void removeFlipsBackToNotConfiguredAndSecondRemoveIsANoOp() throws VaultException {
        assertTrue(vault.remove(ApiKeySource.VIRUSTOTAL.entryName()));

        List<ApiKeyRow> rows = ApiKeyRows.from(vault.keys());
        for (ApiKeyRow row : rows) {
            assertFalse(row.configured());
        }

        assertFalse(vault.remove(ApiKeySource.VIRUSTOTAL.entryName()));
    }

    @Test
    @Order(4)
    void addedKeySurvivesReopenAndRemovedKeyStaysAbsentAfterReopen() throws VaultException {
        vault.put(ApiKeySource.SHODAN.entryName(), "shodan-durability-value");

        try (Vault reopened = store.open(operator, PASSWORD.clone())) {
            assertTrue(reopened.keys().contains(ApiKeySource.SHODAN.entryName()));
        }

        vault.remove(ApiKeySource.SHODAN.entryName());

        try (Vault reopenedAfterRemove = store.open(operator, PASSWORD.clone())) {
            assertFalse(reopenedAfterRemove.keys().contains(ApiKeySource.SHODAN.entryName()));
        }
    }

    @Test
    @Order(5)
    void handAddedEntrySurvivesEveryCatalogueKeyBeingAddedAndRemoved() throws VaultException {
        vault.put("operator-note", "not a catalogue entry");

        for (ApiKeySource source : ApiKeySource.values()) {
            vault.put(source.entryName(), "value-for-" + source.sourceName());
        }
        for (ApiKeySource source : ApiKeySource.values()) {
            vault.remove(source.entryName());
        }

        assertTrue(vault.keys().contains("operator-note"));
        List<ApiKeyRow> rows = ApiKeyRows.from(vault.keys());
        for (ApiKeyRow row : rows) {
            assertFalse(row.configured());
        }

        vault.remove("operator-note");
    }

    @Test
    @Order(6)
    void vaultFileBytesContainNeitherStoredValueAsASubstring() throws Exception {
        String marker = "DISTINCTIVE-ROUND-TRIP-MARKER-VALUE";
        vault.put(ApiKeySource.ABUSEIPDB.entryName(), marker);

        byte[] raw = Files.readAllBytes(store.fileFor(operator));
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        assertFalse(asUtf8.contains(marker));
        assertTrue(vault.keys().stream().noneMatch(key -> key.equals(marker)));

        vault.remove(ApiKeySource.ABUSEIPDB.entryName());
    }

    @ParameterizedTest
    @Order(7)
    @EnumSource(ApiKeySource.class)
    void everyCatalogueEntryNameRoundTrips(ApiKeySource source) throws VaultException {
        vault.put(source.entryName(), "round-trip-value-for-" + source.sourceName());

        List<ApiKeyRow> rows = ApiKeyRows.from(vault.keys());
        for (ApiKeyRow row : rows) {
            assertEquals(row.source() == source, row.configured());
        }

        vault.remove(source.entryName());
    }
}
