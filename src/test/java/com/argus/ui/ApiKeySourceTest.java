package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ApiKeyNames;
import com.argus.core.ShodanSource;
import com.argus.core.VirusTotalSource;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiKeySource} — the fixed, closed catalogue of threat-intel providers (plan §6.3).
 * Toolkit-free: no javafx.* import.
 */
class ApiKeySourceTest {

    private static final Pattern SOURCE_NAME_CHARSET = Pattern.compile("[a-z0-9]+");

    @Test
    void exactlyFourConstantsInDeclarationOrder() {
        ApiKeySource[] values = ApiKeySource.values();
        assertEquals(4, values.length);
        assertEquals(ApiKeySource.VIRUSTOTAL, values[0]);
        assertEquals(ApiKeySource.SHODAN, values[1]);
        assertEquals(ApiKeySource.ABUSEIPDB, values[2]);
        assertEquals(ApiKeySource.CENSYS, values[3]);
    }

    @Test
    void everySourceNameMatchesTheIntelSourceCharsetAndIsUnique() {
        Set<String> seen = new HashSet<>();
        for (ApiKeySource source : ApiKeySource.values()) {
            assertTrue(SOURCE_NAME_CHARSET.matcher(source.sourceName()).matches(),
                    source + " sourceName() must match [a-z0-9]+: " + source.sourceName());
            assertTrue(seen.add(source.sourceName()), "duplicate sourceName: " + source.sourceName());
        }
    }

    @Test
    void entryNameEqualsApiKeyNamesForSourceOfSourceName() {
        for (ApiKeySource source : ApiKeySource.values()) {
            assertEquals(ApiKeyNames.forSource(source.sourceName()), source.entryName());
        }
    }

    @Test
    void displayNameAndAuthHintAreNonBlankAndDistinctPerConstant() {
        Set<String> displayNames = new HashSet<>();
        Set<String> authHints = new HashSet<>();
        for (ApiKeySource source : ApiKeySource.values()) {
            assertFalse(source.displayName().isBlank());
            assertFalse(source.authHint().isBlank());
            assertTrue(displayNames.add(source.displayName()),
                    "duplicate displayName: " + source.displayName());
            assertTrue(authHints.add(source.authHint()),
                    "duplicate authHint: " + source.authHint());
        }
    }

    @Test
    void noAuthHintContainsHttp() {
        for (ApiKeySource source : ApiKeySource.values()) {
            assertFalse(source.authHint().toLowerCase().contains("http"),
                    source + " authHint must not contain a URL: " + source.authHint());
        }
    }

    /**
     * The cross-check P1-07 R4 said "nothing can verify until P2-02 exists" (plan §6.7):
     * ApiKeySource.VIRUSTOTAL must name the same source and vault entry as the real
     * VirusTotalSource IntelSource implementation.
     */
    @Test
    void virustotalCatalogueEntryMatchesTheIntelSourceImplementation() {
        assertEquals(VirusTotalSource.NAME, ApiKeySource.VIRUSTOTAL.sourceName());
        assertEquals(ApiKeySource.VIRUSTOTAL.entryName(),
                ApiKeyNames.forSource(VirusTotalSource.NAME));
    }

    /**
     * Shodan's share of the same cross-check (plan §6.7). P2-04 and P2-05 each still owe the
     * same one-line assertion.
     */
    @Test
    void shodanCatalogueEntryMatchesTheIntelSourceImplementation() {
        assertEquals(ShodanSource.NAME, ApiKeySource.SHODAN.sourceName());
        assertEquals(ApiKeySource.SHODAN.entryName(),
                ApiKeyNames.forSource(ShodanSource.NAME));
    }
}
