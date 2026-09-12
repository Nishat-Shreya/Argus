package com.argus.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * The vault files in one directory: one file per operator (plan §7.4). Immutable, stateless
 * and thread-safe — it holds a {@code Path} and an iteration count, nothing else.
 *
 * BLOCKING: every method does file I/O plus (in production) ~600,000 PBKDF2 iterations. Never
 * call one on the JavaFX Application Thread (invariant 3); the UI wraps it in a Task (§4.4).
 */
public final class VaultStore {

    /** Minimum master password length, enforced on creation only (plan §7.7). */
    public static final int MIN_MASTER_PASSWORD_LENGTH = 8;

    private final Path directory;
    private final int iterations;

    private VaultStore(Path directory, int iterations) {
        this.directory = directory;
        this.iterations = iterations;
    }

    /** A store over an explicit directory. What every test uses ({@code @TempDir}). */
    public static VaultStore at(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return new VaultStore(directory, VaultCrypto.ITERATIONS);
    }

    /** A store over the per-user application data directory (plan §7.5). What App uses. */
    public static VaultStore atDefaultLocation() {
        return new VaultStore(AppDataDirectory.resolve().resolve("vaults"), VaultCrypto.ITERATIONS);
    }

    /**
     * Package-private test seam (plan §8 R1): a low iteration count so the suite does not pay
     * ~600,000 PBKDF2 iterations per test. The production factories above always use
     * {@code VaultCrypto.ITERATIONS}. No public API may expose a tunable iteration count —
     * that would be a downgrade lever in production code.
     */
    static VaultStore forTesting(Path directory, int iterations) {
        return new VaultStore(directory, iterations);
    }

    public Path directory() {
        return directory;
    }

    /** {@code directory().resolve(operator.fileName())}. Diagnostics and tests. */
    public Path fileFor(OperatorId operator) {
        return directory.resolve(operator.fileName());
    }

    /** True if a vault file exists for this operator. No decryption, no password needed. */
    public boolean exists(OperatorId operator) {
        return Files.exists(fileFor(operator));
    }

    /**
     * Creates a new, empty, encrypted vault and returns it unlocked.
     *
     * @throws IllegalArgumentException    if the password is shorter than
     *                                     MIN_MASTER_PASSWORD_LENGTH
     * @throws VaultAlreadyExistsException if a file already exists for this operator — never
     *                                     clobbers (plan §7.7)
     * @throws VaultException              on any I/O or crypto failure
     */
    public Vault create(OperatorId operator, char[] masterPassword) throws VaultException {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(masterPassword, "masterPassword");
        if (masterPassword.length < MIN_MASTER_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "master password must be at least " + MIN_MASTER_PASSWORD_LENGTH
                            + " characters");
        }

        Path file = fileFor(operator);
        if (Files.exists(file)) {
            throw new VaultAlreadyExistsException(
                    "a vault already exists for operator '" + operator + "'");
        }

        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new VaultException("failed to create vault directory: " + directory, e);
        }

        byte[] salt = VaultCrypto.randomSalt();
        byte[] key = VaultCrypto.deriveKey(masterPassword, salt, operator.value(), iterations);

        Vault vault = new Vault(operator, file, key, salt, VaultCrypto.KDF_ALGORITHM, iterations,
                Map.of());
        vault.writeInitialFile();
        return vault;
    }

    /**
     * Opens an existing vault.
     *
     * @throws VaultNotFoundException       if there is no file for this operator
     * @throws VaultFormatException         if the file is not a readable argus-vault envelope
     * @throws WrongMasterPasswordException if the GCM tag does not verify — i.e. the password
     *                                      or the operator id is wrong, or the file was
     *                                      modified
     * @throws VaultException               on any I/O failure
     */
    public Vault open(OperatorId operator, char[] masterPassword) throws VaultException {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(masterPassword, "masterPassword");

        Path file = fileFor(operator);
        if (!Files.exists(file)) {
            throw new VaultNotFoundException("no vault for operator '" + operator + "'");
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new VaultException("failed to read vault file: " + file, e);
        }

        VaultEnvelope envelope = VaultEnvelope.read(fileBytes);

        byte[] key = VaultCrypto.deriveKey(
                masterPassword, envelope.salt(), operator.value(), envelope.iterations());
        byte[] aad = envelope.additionalAuthenticatedData();

        byte[] plaintext;
        try {
            plaintext = VaultCrypto.decrypt(key, envelope.iv(), aad, envelope.ciphertext());
        } catch (WrongMasterPasswordException e) {
            Arrays.fill(key, (byte) 0);
            throw e;
        }

        Map<String, String> entries;
        try {
            entries = Vault.parseEntries(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        return new Vault(operator, file, key, envelope.salt(), envelope.kdf(),
                envelope.iterations(), entries);
    }
}
