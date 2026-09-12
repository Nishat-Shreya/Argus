package com.argus.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An unlocked vault: API key name -&gt; API key value, write-through to an AES-256-GCM file.
 *
 * Unlocked-ness is a TYPE, not a flag (plan §7.2). There is no public constructor and no
 * unlock() method here; the only way to hold a Vault is to have passed
 * VaultStore.open/create. Nothing downstream ever has to ask "is it unlocked?", and there is
 * no isLocked()/IllegalStateException path threaded through every getter.
 *
 * Thread-safe: all state is guarded by one private final lock (plan §4.2). Zero volatile,
 * zero atomics, zero concurrent collections — put/remove are check-then-act compound
 * operations on the map plus a file rewrite, so a volatile flag would be unsafe (invariant 5).
 *
 * BLOCKING: put/remove encrypt and rewrite the file. Not for the FX thread (invariant 3), and
 * never called while holding the ScanPipeline lock (P1-03 §4.3.5).
 */
public final class Vault implements AutoCloseable {

    /** Maximum length of an entry name. */
    public static final int MAX_NAME_LENGTH = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Object lock = new Object();

    private final OperatorId operator; // immutable
    private final Path file; // immutable
    private final byte[] salt; // immutable for this vault's lifetime; the key is not re-derived
    private final String kdf; // immutable
    private final int iterations; // immutable

    private final Map<String, String> entries; // @GuardedBy("lock")
    private byte[] key; // @GuardedBy("lock"); zeroed by close()
    private boolean closed; // @GuardedBy("lock")

    /** Package-private: only {@link VaultStore} may construct a Vault. */
    Vault(OperatorId operator, Path file, byte[] key, byte[] salt, String kdf, int iterations,
            Map<String, String> entries) {
        this.operator = operator;
        this.file = file;
        this.key = key;
        this.salt = salt;
        this.kdf = kdf;
        this.iterations = iterations;
        this.entries = new HashMap<>(entries);
    }

    /** Who this vault belongs to. Safe to display. */
    public OperatorId operator() {
        return operator;
    }

    /** The file backing this vault. Diagnostics only. */
    public Path file() {
        return file;
    }

    /**
     * @throws IllegalArgumentException on a blank name
     * @throws IllegalStateException    if this vault is closed
     */
    public Optional<String> get(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("entry name must not be blank");
        }
        synchronized (lock) {
            ensureOpen();
            return Optional.ofNullable(entries.get(name));
        }
    }

    /**
     * Stores (or replaces) an entry and rewrites the file immediately with a FRESH IV
     * (plan §4.3).
     *
     * @throws IllegalArgumentException if name or value is blank, or name exceeds
     *                                   MAX_NAME_LENGTH
     * @throws IllegalStateException    if this vault is closed
     * @throws VaultException           if the file could not be rewritten — on failure the
     *                                   in-memory map is left unchanged too (plan §4.3)
     */
    public void put(String name, String value) throws VaultException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("entry name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "entry name exceeds " + MAX_NAME_LENGTH + " characters");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("entry value must not be blank");
        }
        synchronized (lock) {
            ensureOpen();
            Map<String, String> snapshot = new HashMap<>(entries);
            snapshot.put(name, value);
            writeThroughLocked(snapshot);
            entries.put(name, value);
        }
    }

    /** @return true if an entry was removed. Removing an absent name is a no-op, not an error. */
    public boolean remove(String name) throws VaultException {
        synchronized (lock) {
            ensureOpen();
            if (!entries.containsKey(name)) {
                return false;
            }
            Map<String, String> snapshot = new HashMap<>(entries);
            snapshot.remove(name);
            writeThroughLocked(snapshot);
            entries.remove(name);
            return true;
        }
    }

    /** Entry names, alphabetical, immutable snapshot. Never the values. */
    public List<String> keys() {
        synchronized (lock) {
            ensureOpen();
            return entries.keySet().stream().sorted().toList();
        }
    }

    /**
     * Locks the vault: zeroes the derived key, clears the entry map, releases the handle.
     * Idempotent. Every subsequent get/put/remove/keys throws IllegalStateException.
     *
     * Named close() rather than lock() so try-with-resources works in tests and in
     * App.stop(); "lock" is what it means.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(key, (byte) 0);
            entries.clear();
        }
    }

    /** {@code Vault[operator=nishat, entries=3, closed=false]} — NEVER names or values (§7.8). */
    @Override
    public String toString() {
        synchronized (lock) {
            return "Vault[operator=" + operator.value() + ", entries=" + entries.size()
                    + ", closed=" + closed + "]";
        }
    }

    /** Package-private: VaultStore.create() materializes the (empty) file exactly once. */
    void writeInitialFile() throws VaultException {
        synchronized (lock) {
            writeThroughLocked(entries);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("vault for operator '" + operator + "' is closed");
        }
    }

    /** Must be called under {@code lock}. */
    private void writeThroughLocked(Map<String, String> snapshot) throws VaultException {
        byte[] plaintext;
        try {
            plaintext = MAPPER.writeValueAsBytes(snapshot);
        } catch (IOException e) {
            throw new VaultException("failed to serialize vault entries", e);
        }

        byte[] iv = VaultCrypto.randomIv();
        // AAD depends only on the header fields, never on the ciphertext, so build it against
        // a placeholder before the ciphertext exists.
        VaultEnvelope header =
                new VaultEnvelope(VaultEnvelope.VERSION, kdf, iterations, salt, iv,
                        operator.value(), new byte[0]);
        byte[] aad = header.additionalAuthenticatedData();

        byte[] ciphertext;
        try {
            ciphertext = VaultCrypto.encrypt(key, iv, aad, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        VaultEnvelope envelope = new VaultEnvelope(VaultEnvelope.VERSION, kdf, iterations, salt,
                iv, operator.value(), ciphertext);
        writeAtomic(file, envelope.toJsonBytes());
    }

    /**
     * Atomic write (plan §7.6): serialise -&gt; write {@code <name>.tmp} in the same directory
     * with {@code CREATE_NEW} -&gt; {@code force(true)} -&gt; {@code Files.move} with
     * {@code ATOMIC_MOVE, REPLACE_EXISTING} -&gt; delete the temp file in a {@code finally} if
     * the move never happened.
     */
    private static void writeAtomic(Path target, byte[] content) throws VaultException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content));
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Plan §8 R5: ATOMIC_MOVE is unsupported on this filesystem. Falls back to a
                // plain move (losing the atomicity guarantee, not silently) rather than
                // abandoning the temp-file pattern for a direct truncating write.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            setOwnerOnlyIfPossible(target);
        } catch (IOException e) {
            throw new VaultException("failed to write vault file: " + target, e);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /** Best-effort on Windows (no POSIX permissions there); enforced where POSIX exists. */
    private static void setOwnerOnlyIfPossible(Path file) {
        try {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view != null) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // best-effort; not a correctness requirement (plan §7.5)
        }
    }

    static Map<String, String> parseEntries(byte[] plaintext) throws VaultFormatException {
        Objects.requireNonNull(plaintext, "plaintext");
        try {
            return MAPPER.readValue(plaintext, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            throw new VaultFormatException("vault plaintext is not a valid entry map", e);
        }
    }
}
