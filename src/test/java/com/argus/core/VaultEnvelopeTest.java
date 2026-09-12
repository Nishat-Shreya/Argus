package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 6.3: the on-disk structure -- strict, hand-rolled Jackson tree parsing. */
class VaultEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] SALT = fill((byte) 0x11, 16);
    private static final byte[] IV = fill((byte) 0x22, 12);
    private static final byte[] CIPHERTEXT = fill((byte) 0x33, 24);

    @Test
    void writeThenReadRoundTrips() throws VaultFormatException {
        VaultEnvelope original = new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "nishat", CIPHERTEXT);

        VaultEnvelope roundTripped = VaultEnvelope.read(original.toJsonBytes());

        assertEquals(original.version(), roundTripped.version());
        assertEquals(original.kdf(), roundTripped.kdf());
        assertEquals(original.iterations(), roundTripped.iterations());
        assertArrayEquals(original.salt(), roundTripped.salt());
        assertArrayEquals(original.iv(), roundTripped.iv());
        assertEquals(original.operatorId(), roundTripped.operatorId());
        assertArrayEquals(original.ciphertext(), roundTripped.ciphertext());
    }

    @Test
    void jsonIsReadableAndCarriesTheFormatMarker() throws Exception {
        VaultEnvelope envelope = new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "nishat", CIPHERTEXT);

        JsonNode node = MAPPER.readTree(envelope.toJsonBytes());

        assertEquals("argus-vault", node.get("format").asText());
        assertEquals(1, node.get("version").asInt());
    }

    @Test
    void readRejectsNonJson() {
        VaultFormatException e = assertThrows(VaultFormatException.class,
                () -> VaultEnvelope.read("not json at all".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getCause() != null);
    }

    @Test
    void readRejectsATruncatedFile() {
        VaultEnvelope envelope = new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "nishat", CIPHERTEXT);
        byte[] full = envelope.toJsonBytes();
        byte[] truncated = new byte[full.length / 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(truncated));
    }

    @Test
    void readRejectsAnEmptyFile() {
        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(new byte[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"x\"", "5"})
    void readRejectsAJsonArrayOrScalar(String json) {
        assertThrows(VaultFormatException.class,
                () -> VaultEnvelope.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "version", "operatorId", "kdf", "iterations", "salt", "iv",
            "ciphertext"})
    void readRejectsAMissingField(String fieldToRemove) {
        ObjectNode node = validNode();
        node.remove(fieldToRemove);

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(node)));
    }

    @Test
    void readRejectsAnUnknownVersion() {
        ObjectNode node = validNode();
        node.put("version", 2);

        VaultFormatException e = assertThrows(VaultFormatException.class,
                () -> VaultEnvelope.read(bytes(node)));
        assertTrue(e.getMessage().contains("2"));
        assertTrue(e.getMessage().contains("1"));
    }

    @Test
    void readRejectsAWrongFormatMarker() {
        ObjectNode node = validNode();
        node.put("format", "not-argus");

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(node)));
    }

    @Test
    void readRejectsBadBase64() {
        ObjectNode node = validNode();
        node.put("salt", "!!!!");

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(node)));
    }

    @Test
    void readRejectsWrongSaltOrIvLength() {
        ObjectNode shortSalt = validNode();
        shortSalt.put("salt", Base64.getEncoder().encodeToString(fill((byte) 1, 8)));
        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(shortSalt)));

        ObjectNode longIv = validNode();
        longIv.put("iv", Base64.getEncoder().encodeToString(fill((byte) 1, 16)));
        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(longIv)));
    }

    @Test
    void readRejectsAnUnknownKdf() {
        ObjectNode node = validNode();
        node.put("kdf", "PBKDF2WithHmacMD5");

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(node)));
    }

    @ParameterizedTest
    @CsvSource({"1", "0", "-5", "50000000"})
    void readRejectsIterationsOutOfRange(int iterations) {
        ObjectNode node = validNode();
        node.put("iterations", iterations);

        assertThrows(VaultFormatException.class, () -> VaultEnvelope.read(bytes(node)));
    }

    @Test
    void aadCoversEveryHeaderField() {
        VaultEnvelope base = new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "nishat", CIPHERTEXT);
        byte[] baseAad = base.additionalAuthenticatedData();

        assertNotEqualAad(baseAad, new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 700_000, SALT, IV, "nishat", CIPHERTEXT));
        assertNotEqualAad(baseAad, new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, fill((byte) 0x99, 16), IV, "nishat",
                CIPHERTEXT));
        assertNotEqualAad(baseAad, new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, fill((byte) 0x99, 12), "nishat",
                CIPHERTEXT));
        assertNotEqualAad(baseAad, new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "someone-else", CIPHERTEXT));
    }

    @Test
    void aadIsStableAcrossRoundTrip() throws VaultFormatException {
        VaultEnvelope original = new VaultEnvelope(
                1, "PBKDF2WithHmacSHA256", 600_000, SALT, IV, "nishat", CIPHERTEXT);
        VaultEnvelope roundTripped = VaultEnvelope.read(original.toJsonBytes());

        assertArrayEquals(
                original.additionalAuthenticatedData(),
                roundTripped.additionalAuthenticatedData());
    }

    private static void assertNotEqualAad(byte[] baseAad, VaultEnvelope other) {
        assertFalse(java.util.Arrays.equals(baseAad, other.additionalAuthenticatedData()));
    }

    private static ObjectNode validNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("format", "argus-vault");
        node.put("version", 1);
        node.put("operatorId", "nishat");
        node.put("kdf", "PBKDF2WithHmacSHA256");
        node.put("iterations", 600_000);
        node.put("salt", Base64.getEncoder().encodeToString(SALT));
        node.put("iv", Base64.getEncoder().encodeToString(IV));
        node.put("ciphertext", Base64.getEncoder().encodeToString(CIPHERTEXT));
        return node;
    }

    private static byte[] bytes(ObjectNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] fill(byte value, int length) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
