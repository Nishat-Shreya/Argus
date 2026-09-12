package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared test helper for the vault suite (plan §6): a valid password constant, a
 * {@code corruptByteAt(Path, int)} helper, and a {@code readJson(Path)} helper. Not a
 * framework.
 */
final class VaultFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VaultFixtures() {
    }

    /** A password that satisfies {@code VaultStore.MIN_MASTER_PASSWORD_LENGTH} everywhere. */
    static final char[] VALID_PASSWORD = "correct horse battery staple".toCharArray();

    /** Parses the raw vault file bytes as a generic Jackson tree, for field-level assertions. */
    static JsonNode readJson(Path file) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(file));
    }

    /**
     * Flips one bit of the vault's ciphertext, identified by a logical index into the
     * <em>decoded</em> ciphertext bytes (negative counts from the end, like Python) rather than
     * a raw file offset -- the surrounding JSON's length varies with the iteration count and
     * operator id a given test uses, so a raw byte offset would be fragile. Rewrites the file
     * with the corrupted envelope; every other header field is left untouched.
     */
    static void corruptByteAt(Path file, int index) throws IOException, VaultFormatException {
        VaultEnvelope envelope = VaultEnvelope.read(Files.readAllBytes(file));
        byte[] ciphertext = envelope.ciphertext().clone();
        int resolved = index < 0 ? ciphertext.length + index : index;
        ciphertext[resolved] ^= 0x01;
        VaultEnvelope corrupted = new VaultEnvelope(envelope.version(), envelope.kdf(),
                envelope.iterations(), envelope.salt(), envelope.iv(), envelope.operatorId(),
                ciphertext);
        Files.write(file, corrupted.toJsonBytes());
    }
}
