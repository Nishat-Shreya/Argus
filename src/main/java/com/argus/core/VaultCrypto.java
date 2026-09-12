package com.argus.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Key derivation and authenticated encryption. Package-private and static-only: one place in
 * the codebase imports {@code javax.crypto}, so one place has to be reviewed for crypto
 * correctness (plan §3.4).
 *
 * {@code SecureRandom} choice: plain {@code new SecureRandom()}, deliberately NOT
 * {@code SecureRandom.getInstanceStrong()} — on Linux that can map to a blocking source and
 * would hang the build on a low-entropy CI runner (plan §4.3).
 */
final class VaultCrypto {

    static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final String CIPHER = "AES/GCM/NoPadding";
    static final int KEY_BITS = 256;
    static final int GCM_TAG_BITS = 128;
    static final int IV_BYTES = 12;
    static final int SALT_BYTES = 16;
    static final int ITERATIONS = 600_000; // OWASP, plan §7.1
    static final int MIN_ITERATIONS = 100_000; // downgrade floor when READING a file
    static final int MAX_ITERATIONS = 5_000_000; // DoS ceiling when READING a file

    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() {
    }

    /** PBKDF2 over (password, salt || UTF-8(operatorId), iterations) -> 32 raw bytes (§7.3). */
    static byte[] deriveKey(char[] masterPassword, byte[] salt, String operatorId, int iterations) {
        byte[] operatorIdBytes = operatorId.getBytes(StandardCharsets.UTF_8);
        byte[] kdfSalt = new byte[salt.length + operatorIdBytes.length];
        System.arraycopy(salt, 0, kdfSalt, 0, salt.length);
        System.arraycopy(operatorIdBytes, 0, kdfSalt, salt.length, operatorIdBytes.length);

        PBEKeySpec spec = new PBEKeySpec(masterPassword, kdfSalt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            SecretKey key = factory.generateSecret(spec);
            return key.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 key derivation failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** AES-256-GCM. Returns ciphertext||tag. */
    static byte[] encrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @throws WrongMasterPasswordException on AEADBadTagException — the ONLY clean failure signal */
    static byte[] decrypt(byte[] key, byte[] iv, byte[] aad, byte[] ciphertext)
            throws WrongMasterPasswordException {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new WrongMasterPasswordException(
                    "operator id or master password is wrong, or the vault file was modified", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    /** {@code IV_BYTES} fresh bytes from the shared SecureRandom. */
    static byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }
}
