package com.argus.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The cleartext header + opaque ciphertext of a vault file (plan §3.5/§7.6). Nothing here is
 * secret: the format marker, version, KDF name, iteration count, salt, IV and operator id are
 * cleartext by design (they are what makes "look at the file and see no secrets" possible).
 * Only {@code ciphertext} is opaque.
 */
record VaultEnvelope(int version, String kdf, int iterations, byte[] salt, byte[] iv,
                      String operatorId, byte[] ciphertext) {

    static final String FORMAT = "argus-vault";
    static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> REQUIRED_FIELDS = List.of(
            "format", "version", "operatorId", "kdf", "iterations", "salt", "iv", "ciphertext");

    /** Jackson tree -> envelope, with every field range-checked. */
    static VaultEnvelope read(byte[] fileBytes) throws VaultFormatException {
        JsonNode root;
        try {
            root = MAPPER.readTree(fileBytes);
        } catch (IOException e) {
            throw new VaultFormatException("vault file is not valid JSON", e);
        }
        if (root == null || root.isMissingNode()) {
            throw new VaultFormatException("vault file is empty");
        }
        if (!root.isObject()) {
            throw new VaultFormatException("vault file must contain a JSON object");
        }

        for (String field : REQUIRED_FIELDS) {
            if (!root.hasNonNull(field)) {
                throw new VaultFormatException("vault file is missing required field: " + field);
            }
        }

        String format = root.get("format").asText();
        if (!FORMAT.equals(format)) {
            throw new VaultFormatException(
                    "vault file has an unrecognised format marker: " + format);
        }

        if (!root.get("version").isIntegralNumber()) {
            throw new VaultFormatException("vault file version must be an integer");
        }
        int version = root.get("version").asInt();
        if (version != VERSION) {
            throw new VaultFormatException(
                    "vault file version " + version + " is not supported (expected " + VERSION
                            + ")");
        }

        String kdf = root.get("kdf").asText();
        if (!VaultCrypto.KDF_ALGORITHM.equals(kdf)) {
            throw new VaultFormatException("vault file uses an unrecognised KDF: " + kdf);
        }

        if (!root.get("iterations").isIntegralNumber()) {
            throw new VaultFormatException("vault file iterations must be an integer");
        }
        int iterations = root.get("iterations").asInt();
        if (iterations < VaultCrypto.MIN_ITERATIONS || iterations > VaultCrypto.MAX_ITERATIONS) {
            throw new VaultFormatException(
                    "vault file iterations " + iterations + " is out of the allowed range ["
                            + VaultCrypto.MIN_ITERATIONS + ", " + VaultCrypto.MAX_ITERATIONS
                            + "]");
        }

        byte[] salt = decodeBase64Field(root, "salt");
        if (salt.length != VaultCrypto.SALT_BYTES) {
            throw new VaultFormatException(
                    "vault file salt must be " + VaultCrypto.SALT_BYTES + " bytes, was "
                            + salt.length);
        }

        byte[] iv = decodeBase64Field(root, "iv");
        if (iv.length != VaultCrypto.IV_BYTES) {
            throw new VaultFormatException(
                    "vault file iv must be " + VaultCrypto.IV_BYTES + " bytes, was " + iv.length);
        }

        String operatorId = root.get("operatorId").asText();
        if (operatorId.isBlank()) {
            throw new VaultFormatException("vault file operatorId must not be blank");
        }

        byte[] ciphertext = decodeBase64Field(root, "ciphertext");

        return new VaultEnvelope(version, kdf, iterations, salt, iv, operatorId, ciphertext);
    }

    private static byte[] decodeBase64Field(JsonNode root, String field) throws VaultFormatException {
        String text = root.get(field).asText();
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new VaultFormatException("vault file field '" + field + "' is not valid base64", e);
        }
    }

    /** Envelope -> pretty-printed JSON bytes. */
    byte[] toJsonBytes() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("format", FORMAT);
        node.put("version", version);
        node.put("operatorId", operatorId);
        node.put("kdf", kdf);
        node.put("iterations", iterations);
        node.put("salt", Base64.getEncoder().encodeToString(salt));
        node.put("iv", Base64.getEncoder().encodeToString(iv));
        node.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize vault envelope", e);
        }
    }

    /**
     * The GCM additional authenticated data: every header field, in a fixed order, so that
     * editing any of them is detected (plan §7.6):
     * {@code "argus-vault|1|PBKDF2WithHmacSHA256|600000|<b64 salt>|<b64 iv>|<operatorId>"} (UTF-8)
     */
    byte[] additionalAuthenticatedData() {
        String aad = FORMAT + "|" + version + "|" + kdf + "|" + iterations + "|"
                + Base64.getEncoder().encodeToString(salt) + "|"
                + Base64.getEncoder().encodeToString(iv) + "|" + operatorId;
        return aad.getBytes(StandardCharsets.UTF_8);
    }
}
