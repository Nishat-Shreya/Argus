package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 6.4 group A: the acceptance criteria for the unlocked {@link Vault}. Uses
 * {@code VaultStore.forTesting} throughout (a low PBKDF2 iteration count) so the suite does not
 * pay ~600,000 iterations per test (plan §8 R1); {@link VaultCryptoTest} already exercises the
 * real production constant.
 */
class VaultRoundTripTest {

    // VaultCrypto.MIN_ITERATIONS (100,000) is a read-side floor enforced on every open(), so a
    // test vault cannot use fewer iterations than that without every re-open failing format
    // validation. This is still ~6x cheaper than the production constant (plan §8 R1).
    private static final int TEST_ITERATIONS = 100_000;

    @Test
    void createWritesAFileAndReturnsAnEmptyVault(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertTrue(Files.exists(store.fileFor(operator)));
            assertTrue(vault.keys().isEmpty());
            assertEquals(Optional.empty(), vault.get("anything"));
        }
    }

    @Test
    void putThenReopenReturnsTheValue(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("virustotal", "SECRET-VALUE-1");
        }

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(Optional.of("SECRET-VALUE-1"), reopened.get("virustotal"));
        }
    }

    @Test
    void emptyVaultReopensCleanly(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            // no entries
        }

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertTrue(reopened.keys().isEmpty());
        }
    }

    @Test
    void putIsWriteThroughWithoutClose(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("shodan", "value-a");

            try (Vault secondHandle = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
                assertEquals(Optional.of("value-a"), secondHandle.get("shodan"));
            }
        }
    }

    @Test
    void putOverwritesAnExistingEntry(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("shodan", "value-a");
            vault.put("shodan", "value-b");

            assertEquals(1, vault.keys().size());
            assertEquals(Optional.of("value-b"), vault.get("shodan"));
        }
    }

    @Test
    void removeDeletesAndPersists(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("shodan", "value-a");
            assertTrue(vault.remove("shodan"));
        }

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(Optional.empty(), reopened.get("shodan"));
        }
    }

    @Test
    void removingAnAbsentEntryReturnsFalseAndIsNotAnError(@TempDir Path tempDir)
            throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertFalse(vault.remove("does-not-exist"));
        }
    }

    @Test
    void keysAreAlphabeticalAndImmutable(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("shodan", "v1");
            vault.put("abuseipdb", "v2");
            vault.put("censys", "v3");

            List<String> keys = vault.keys();
            assertEquals(List.of("abuseipdb", "censys", "shodan"), keys);
            assertThrows(UnsupportedOperationException.class, () -> keys.add("x"));
        }
    }

    @Test
    void everyWriteUsesAFreshIv(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Path file = store.fileFor(operator);
        Set<String> ivs = new HashSet<>();

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            ivs.add(Base64.getEncoder().encodeToString(VaultEnvelope.read(Files.readAllBytes(file)).iv()));
            vault.put("a", "1");
            ivs.add(Base64.getEncoder().encodeToString(VaultEnvelope.read(Files.readAllBytes(file)).iv()));
            vault.put("b", "2");
            ivs.add(Base64.getEncoder().encodeToString(VaultEnvelope.read(Files.readAllBytes(file)).iv()));
        }

        assertEquals(3, ivs.size());
    }

    @Test
    void theSaltIsStableAcrossWrites(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Path file = store.fileFor(operator);

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            byte[] saltBefore = VaultEnvelope.read(Files.readAllBytes(file)).salt();
            vault.put("a", "1");
            byte[] saltAfter = VaultEnvelope.read(Files.readAllBytes(file)).salt();
            assertTrue(Arrays.equals(saltBefore, saltAfter));
        }
    }

    @Test
    void eachOperatorGetsItsOwnFile(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId alice = OperatorId.of("alice");
        OperatorId bob = OperatorId.of("bob");

        try (Vault aliceVault = store.create(alice, VaultFixtures.VALID_PASSWORD.clone())) {
            aliceVault.put("k", "alice-secret");
        }
        try (Vault bobVault = store.create(bob, VaultFixtures.VALID_PASSWORD.clone())) {
            bobVault.put("k", "bob-secret");
        }

        assertFalse(store.fileFor(alice).equals(store.fileFor(bob)));

        try (Vault reopenedAlice = store.open(alice, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(Optional.of("alice-secret"), reopenedAlice.get("k"));
        }
        try (Vault reopenedBob = store.open(bob, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(Optional.of("bob-secret"), reopenedBob.get("k"));
        }
    }

    @Test
    void createdFileIsOwnerOnlyWherePosixIsSupported(@TempDir Path tempDir) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            Path file = store.fileFor(operator);
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            assumeTrue(view != null);
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms);
        }
    }

    @Test
    void noTemporaryFileIsLeftBehind(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("a", "1");
            vault.put("b", "2");
            vault.put("c", "3");
        }

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(tempDir)) {
            stream.forEach(files::add);
        }
        assertEquals(1, files.size());
    }

    @Test
    void vaultToStringLeaksNothing(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("virustotal", "TOP-SECRET-VALUE");
            String text = vault.toString();
            assertFalse(text.contains("virustotal"));
            assertFalse(text.contains("TOP-SECRET-VALUE"));
        }
    }

    @Test
    void openDoesNotMutateTheCallersPasswordArray(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            // no-op
        }

        char[] password = VaultFixtures.VALID_PASSWORD.clone();
        char[] original = password.clone();
        try (Vault reopened = store.open(operator, password)) {
            assertTrue(Arrays.equals(original, password));
        }
    }

    @Test
    void closeIsIdempotentAndSubsequentUseThrows(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone());

        vault.close();
        vault.close(); // idempotent, must not throw

        assertThrows(IllegalStateException.class, () -> vault.get("x"));
        assertThrows(IllegalStateException.class, () -> vault.put("x", "y"));
        assertThrows(IllegalStateException.class, () -> vault.remove("x"));
        assertThrows(IllegalStateException.class, vault::keys);
    }

    @Test
    void putRejectsBlankNameOrValue(@TempDir Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertThrows(IllegalArgumentException.class, () -> vault.put("", "value"));
            assertThrows(IllegalArgumentException.class, () -> vault.put("name", ""));
            assertThrows(IllegalArgumentException.class, () -> vault.put(null, "value"));
        }
    }

    // --- invariant-7 "no plaintext in the file" tests ---

    @Test
    void theSecretValueIsAbsentFromTheRawFileBytes(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        String secret = "MY-DISTINCTIVE-SECRET-VALUE-42";

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("virustotal", secret);
        }

        byte[] raw = Files.readAllBytes(store.fileFor(operator));
        assertEquals(-1, indexOf(raw, secret.getBytes(StandardCharsets.UTF_8)));

        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        String asLatin1 = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse(asUtf8.contains(secret));
        assertFalse(asLatin1.contains(secret));
    }

    @Test
    void theEntryNameIsAlsoAbsentFromTheRawFile(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("distinctive-entry-name-xyz", "some-value");
        }

        byte[] raw = Files.readAllBytes(store.fileFor(operator));
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        assertFalse(asUtf8.contains("distinctive-entry-name-xyz"));
    }

    @Test
    void theMasterPasswordIsAbsentFromTheRawFile(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        String password = "correct horse battery staple";

        try (Vault vault = store.create(operator, password.toCharArray())) {
            vault.put("k", "v");
        }

        byte[] raw = Files.readAllBytes(store.fileFor(operator));
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        assertFalse(asUtf8.contains(password));
    }

    @Test
    void theFileIsNotMerelyBase64EncodedPlaintext(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        String secret = "ANOTHER-DISTINCTIVE-SECRET-99";

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            vault.put("k", secret);
        }

        var node = VaultFixtures.readJson(store.fileFor(operator));
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        for (var entry : node.properties()) {
            if (!entry.getValue().isTextual()) {
                continue;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(entry.getValue().asText());
                assertEquals(-1, indexOf(decoded, needle),
                        "field '" + entry.getKey() + "' base64-decodes to reveal the secret");
            } catch (IllegalArgumentException notBase64) {
                // not every field value is base64 (e.g. operatorId); that is fine
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
