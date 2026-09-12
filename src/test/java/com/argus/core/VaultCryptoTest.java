package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Section 6.2: the crypto primitives (package-private access, same package). */
class VaultCryptoTest {

    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    // --- deriveKey: T11-T16, real production ITERATIONS constant ("a handful of times", R1) ---

    @Test
    void deriveKeyIsDeterministic() {
        byte[] salt = VaultCrypto.randomSalt();
        byte[] key1 = VaultCrypto.deriveKey(PASSWORD.clone(), salt, "nishat", VaultCrypto.ITERATIONS);
        byte[] key2 = VaultCrypto.deriveKey(PASSWORD.clone(), salt, "nishat", VaultCrypto.ITERATIONS);
        assertArrayEquals(key1, key2);
    }

    @Test
    void derivedKeyIs256Bits() {
        byte[] key = VaultCrypto.deriveKey(
                PASSWORD.clone(), VaultCrypto.randomSalt(), "nishat", VaultCrypto.ITERATIONS);
        assertEquals(32, key.length);
    }

    @Test
    void differentSaltGivesDifferentKey() {
        byte[] key1 = VaultCrypto.deriveKey(
                PASSWORD.clone(), VaultCrypto.randomSalt(), "nishat", VaultCrypto.ITERATIONS);
        byte[] key2 = VaultCrypto.deriveKey(
                PASSWORD.clone(), VaultCrypto.randomSalt(), "nishat", VaultCrypto.ITERATIONS);
        assertFalse(java.util.Arrays.equals(key1, key2));
    }

    @Test
    void differentPasswordGivesDifferentKey() {
        byte[] salt = VaultCrypto.randomSalt();
        byte[] key1 = VaultCrypto.deriveKey("password-one".toCharArray(), salt, "nishat",
                VaultCrypto.ITERATIONS);
        byte[] key2 = VaultCrypto.deriveKey("password-two".toCharArray(), salt, "nishat",
                VaultCrypto.ITERATIONS);
        assertFalse(java.util.Arrays.equals(key1, key2));
    }

    @Test
    void differentOperatorIdGivesDifferentKey() {
        byte[] salt = VaultCrypto.randomSalt();
        byte[] key1 = VaultCrypto.deriveKey(PASSWORD.clone(), salt, "a", VaultCrypto.ITERATIONS);
        byte[] key2 = VaultCrypto.deriveKey(PASSWORD.clone(), salt, "b", VaultCrypto.ITERATIONS);
        assertFalse(java.util.Arrays.equals(key1, key2));
    }

    @Test
    void deriveKeyDoesNotMutateTheCallersPasswordArray() {
        char[] password = "do-not-touch-me".toCharArray();
        char[] original = password.clone();
        VaultCrypto.deriveKey(password, VaultCrypto.randomSalt(), "nishat", VaultCrypto.ITERATIONS);
        assertArrayEquals(original, password);
    }

    // --- encrypt/decrypt: T17-T27, fixed 32-byte keys, no KDF cost ---

    @Test
    void encryptThenDecryptRoundTrips() throws WrongMasterPasswordException {
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] aad = "header".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = VaultCrypto.encrypt(key, iv, aad, plaintext);
        byte[] roundTripped = VaultCrypto.decrypt(key, iv, aad, ciphertext);

        assertArrayEquals(plaintext, roundTripped);
    }

    @Test
    void ciphertextIsPlaintextLengthPlusSixteen() {
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] plaintext = "0123456789".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = VaultCrypto.encrypt(key, iv, new byte[0], plaintext);

        assertEquals(plaintext.length + 16, ciphertext.length);
    }

    @Test
    void sameInputEncryptedTwiceDiffers() {
        byte[] key = randomKey();
        byte[] plaintext = "repeat me".getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[0];

        byte[] ciphertext1 = VaultCrypto.encrypt(key, VaultCrypto.randomIv(), aad, plaintext);
        byte[] ciphertext2 = VaultCrypto.encrypt(key, VaultCrypto.randomIv(), aad, plaintext);

        assertFalse(java.util.Arrays.equals(ciphertext1, ciphertext2));
    }

    @Test
    void randomIvIsTwelveBytesAndVaries() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            byte[] iv = VaultCrypto.randomIv();
            assertEquals(12, iv.length);
            seen.add(java.util.Base64.getEncoder().encodeToString(iv));
        }
        assertEquals(100, seen.size());
    }

    @Test
    void randomSaltIsSixteenBytesAndVaries() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            byte[] salt = VaultCrypto.randomSalt();
            assertEquals(16, salt.length);
            seen.add(java.util.Base64.getEncoder().encodeToString(salt));
        }
        assertEquals(100, seen.size());
    }

    @Test
    void decryptWithWrongKeyThrowsWrongMasterPassword() {
        byte[] iv = VaultCrypto.randomIv();
        byte[] aad = new byte[0];
        byte[] plaintext = "secret-value".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = VaultCrypto.encrypt(randomKey(), iv, aad, plaintext);

        assertThrows(WrongMasterPasswordException.class,
                () -> VaultCrypto.decrypt(randomKey(), iv, aad, ciphertext));
    }

    @Test
    void decryptWithFlippedCiphertextByteThrowsWrongMasterPassword() {
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] aad = new byte[0];
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = VaultCrypto.encrypt(key, iv, aad, plaintext);
        ciphertext[0] ^= 0x01;

        assertThrows(WrongMasterPasswordException.class,
                () -> VaultCrypto.decrypt(key, iv, aad, ciphertext));
    }

    @Test
    void decryptWithFlippedTagByteThrowsWrongMasterPassword() {
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] aad = new byte[0];
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = VaultCrypto.encrypt(key, iv, aad, plaintext);
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThrows(WrongMasterPasswordException.class,
                () -> VaultCrypto.decrypt(key, iv, aad, ciphertext));
    }

    @Test
    void decryptWithWrongAadThrowsWrongMasterPassword() {
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = VaultCrypto.encrypt(key, iv, "header-a".getBytes(StandardCharsets.UTF_8),
                plaintext);

        assertThrows(WrongMasterPasswordException.class, () -> VaultCrypto.decrypt(
                key, iv, "header-b".getBytes(StandardCharsets.UTF_8), ciphertext));
    }

    @Test
    void decryptWithWrongIvThrowsWrongMasterPassword() {
        byte[] key = randomKey();
        byte[] aad = new byte[0];
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = VaultCrypto.encrypt(key, VaultCrypto.randomIv(), aad, plaintext);

        assertThrows(WrongMasterPasswordException.class,
                () -> VaultCrypto.decrypt(key, VaultCrypto.randomIv(), aad, ciphertext));
    }

    @Test
    void exceptionMessageDoesNotContainThePassword() {
        String passwordText = "super-secret-password-xyz";
        byte[] key = randomKey();
        byte[] iv = VaultCrypto.randomIv();
        byte[] aad = new byte[0];
        byte[] ciphertext = VaultCrypto.encrypt(key, iv, aad,
                passwordText.getBytes(StandardCharsets.UTF_8));
        ciphertext[0] ^= 0x01;

        WrongMasterPasswordException thrown = assertThrows(WrongMasterPasswordException.class,
                () -> VaultCrypto.decrypt(key, iv, aad, ciphertext));

        Throwable current = thrown;
        while (current != null) {
            assertFalse(String.valueOf(current.getMessage()).contains(passwordText));
            current = current.getCause();
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }
}
