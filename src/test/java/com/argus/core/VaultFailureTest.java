package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 6.4 group B: clean, typed failure -- never a garbage read, never a crash. */
class VaultFailureTest {

    private static final int TEST_ITERATIONS = 100_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void wrongPasswordThrowsWrongMasterPasswordException(@TempDir Path tempDir)
            throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("k", "v");
        }

        Exception thrown = assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, "totally-wrong-password".toCharArray()));
        assertTrue(thrown instanceof WrongMasterPasswordException);
    }

    @Test
    void aFailedOpenLeavesTheFileByteForByteUnchanged(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("k", "v");
        }
        Path file = store.fileFor(operator);
        byte[] before = Files.readAllBytes(file);

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, "totally-wrong-password".toCharArray()));

        byte[] after = Files.readAllBytes(file);
        assertArrayEquals(before, after);
    }

    @Test
    void wrongPasswordFailsIdenticallyOnAnEmptyVaultAndAFullOne(@TempDir Path tempDir)
            throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId emptyOperator = OperatorId.of("empty-one");
        OperatorId fullOperator = OperatorId.of("full-one");

        try (Vault vault = store.create(emptyOperator, VaultFixtures.VALID_PASSWORD.clone())) {
            // no entries
        }
        try (Vault vault = store.create(fullOperator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("a", "1");
            vault.put("b", "2");
        }

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(emptyOperator, "wrong".toCharArray()));
        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(fullOperator, "wrong".toCharArray()));
    }

    @Test
    void wrongOperatorIdThrowsWrongMasterPasswordException(@TempDir Path tempDir)
            throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId a = OperatorId.of("operator-a");
        OperatorId b = OperatorId.of("operator-b");
        char[] password = VaultFixtures.VALID_PASSWORD.clone();

        try (Vault vault = store.create(a, password.clone())) {
            vault.put("k", "v");
        }
        Files.copy(store.fileFor(a), store.fileFor(b));

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(b, password.clone()));
    }

    @Test
    void editingTheOperatorIdInsideTheFileIsDetected(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        char[] password = VaultFixtures.VALID_PASSWORD.clone();
        try (Vault vault = store.create(operator, password.clone())) {
            vault.put("k", "v");
        }

        rewriteField(store.fileFor(operator), "operatorId", "someone-else");

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, password.clone()));
    }

    @Test
    void openingAMissingVaultThrowsVaultNotFound(@TempDir Path tempDir) {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nobody-here");

        VaultNotFoundException thrown = assertThrows(VaultNotFoundException.class,
                () -> store.open(operator, VaultFixtures.VALID_PASSWORD.clone()));
        assertTrue(thrown.getMessage().contains("nobody-here"));
    }

    @Test
    void createRefusesToOverwriteAnExistingVault(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("k", "v");
        }

        assertThrows(VaultAlreadyExistsException.class,
                () -> store.create(operator, VaultFixtures.VALID_PASSWORD.clone()));

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(java.util.Optional.of("v"), reopened.get("k"));
        }
    }

    @Test
    void createRejectsAShortMasterPassword(@TempDir Path tempDir) {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        assertThrows(IllegalArgumentException.class,
                () -> store.create(operator, "short12".toCharArray()));
        assertFalse(Files.exists(store.fileFor(operator)));
    }

    @Test
    void openAcceptsAnyLengthPasswordForAnExistingVault(@TempDir Path tempDir)
            throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("k", "v");
        }

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, "short".toCharArray()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void aFlippedCiphertextByteIsDetected(int logicalIndex, @TempDir Path tempDir)
            throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        char[] password = VaultFixtures.VALID_PASSWORD.clone();
        try (Vault vault = store.create(operator, password.clone())) {
            vault.put("k", "a reasonably long value to corrupt safely");
        }

        int index = logicalIndex == Integer.MIN_VALUE ? middleCiphertextIndex(store, operator)
                : logicalIndex;
        VaultFixtures.corruptByteAt(store.fileFor(operator), index);

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, password.clone()));
    }

    @Test
    void anEditedIterationCountIsRejected(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId inRangeOperator = OperatorId.of("in-range");
        OperatorId outOfRangeOperator = OperatorId.of("out-of-range");
        char[] password = VaultFixtures.VALID_PASSWORD.clone();

        try (Vault vault = store.create(inRangeOperator, password.clone())) {
            vault.put("k", "v");
        }
        try (Vault vault = store.create(outOfRangeOperator, password.clone())) {
            vault.put("k", "v");
        }

        rewriteField(store.fileFor(inRangeOperator), "iterations", TEST_ITERATIONS + 1);
        rewriteField(store.fileFor(outOfRangeOperator), "iterations", 1);

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(inRangeOperator, password.clone()));
        assertThrows(VaultFormatException.class,
                () -> store.open(outOfRangeOperator, password.clone()));
    }

    @Test
    void anEditedSaltOrIvIsDetected(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId saltOperator = OperatorId.of("salt-edit");
        OperatorId ivOperator = OperatorId.of("iv-edit");
        char[] password = VaultFixtures.VALID_PASSWORD.clone();

        try (Vault vault = store.create(saltOperator, password.clone())) {
            vault.put("k", "v");
        }
        try (Vault vault = store.create(ivOperator, password.clone())) {
            vault.put("k", "v");
        }

        byte[] differentSalt = new byte[16];
        java.util.Arrays.fill(differentSalt, (byte) 0x5a);
        rewriteField(store.fileFor(saltOperator), "salt",
                Base64.getEncoder().encodeToString(differentSalt));

        byte[] differentIv = new byte[12];
        java.util.Arrays.fill(differentIv, (byte) 0x5a);
        rewriteField(store.fileFor(ivOperator), "iv",
                Base64.getEncoder().encodeToString(differentIv));

        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(saltOperator, password.clone()));
        assertThrows(WrongMasterPasswordException.class,
                () -> store.open(ivOperator, password.clone()));
    }

    @Test
    void garbageFileContentThrowsVaultFormatNotWrongPassword(@TempDir Path tempDir)
            throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Files.createDirectories(tempDir);
        Files.writeString(store.fileFor(operator), "this is not a vault file at all");

        assertThrows(VaultFormatException.class,
                () -> store.open(operator, VaultFixtures.VALID_PASSWORD.clone()));
    }

    @Test
    void aDirectoryInPlaceOfAVaultFileFailsAsVaultException(@TempDir Path tempDir)
            throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Files.createDirectories(store.fileFor(operator));

        assertThrows(VaultException.class,
                () -> store.open(operator, VaultFixtures.VALID_PASSWORD.clone()));
    }

    @Test
    void exceptionMessagesNeverContainTheMasterPassword(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        String realPassword = "the-real-master-password-123";
        String wrongPassword = "definitely-the-wrong-password-456";
        try (Vault vault = store.create(operator, realPassword.toCharArray())) {
            vault.put("k", "v");
        }

        WrongMasterPasswordException thrown = assertThrows(WrongMasterPasswordException.class,
                () -> store.open(operator, wrongPassword.toCharArray()));

        Throwable current = thrown;
        while (current != null) {
            String message = String.valueOf(current.getMessage());
            assertFalse(message.contains(realPassword));
            assertFalse(message.contains(wrongPassword));
            current = current.getCause();
        }
    }

    private static int middleCiphertextIndex(VaultStore store, OperatorId operator)
            throws Exception {
        VaultEnvelope envelope = VaultEnvelope.read(Files.readAllBytes(store.fileFor(operator)));
        return envelope.ciphertext().length / 2;
    }

    private static void rewriteField(Path file, String field, Object newValue) throws Exception {
        ObjectNode node = (ObjectNode) MAPPER.readTree(Files.readAllBytes(file));
        if (newValue instanceof Integer intValue) {
            node.put(field, intValue);
        } else {
            node.put(field, newValue.toString());
        }
        Files.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }
}
