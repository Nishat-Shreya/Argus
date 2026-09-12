package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ApiKeyNames} — the vault entry-name convention, made executable (plan §6.1).
 */
class ApiKeyNamesTest {

    @Test
    void forSourceAppliesThePrefix() {
        assertEquals("apikey.virustotal", ApiKeyNames.forSource("virustotal"));
    }

    @Test
    void prefixIsExactlyApikeyDot() {
        assertEquals("apikey.", ApiKeyNames.PREFIX);
    }

    @Test
    void pinsTheFourLiteralsTheFutureSourcesDependOn() {
        assertEquals("apikey.virustotal", ApiKeyNames.forSource("virustotal"));
        assertEquals("apikey.shodan", ApiKeyNames.forSource("shodan"));
        assertEquals("apikey.abuseipdb", ApiKeyNames.forSource("abuseipdb"));
        assertEquals("apikey.censys", ApiKeyNames.forSource("censys"));
    }

    @Test
    void producedNamesFitWithinVaultMaxNameLength() {
        assertTrue(ApiKeyNames.forSource("virustotal").length() <= Vault.MAX_NAME_LENGTH);
        assertTrue(ApiKeyNames.forSource("a").length() <= Vault.MAX_NAME_LENGTH);
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyNames.forSource(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "VirusTotal", "virus total", "virus.total",
            "virus-total", "apikey.virustotal"})
    void rejectsMalformedSourceNames(String malformed) {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyNames.forSource(malformed));
    }

    @Test
    void producedNamesAreDistinctForDistinctSourceNames() {
        assertNotEquals(ApiKeyNames.forSource("virustotal"), ApiKeyNames.forSource("shodan"));
    }
}
