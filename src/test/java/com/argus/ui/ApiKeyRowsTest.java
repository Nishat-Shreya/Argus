package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiKeyRows} / {@link ApiKeyRow} — the display row model, never carrying key material
 * (plan §6.4).
 */
class ApiKeyRowsTest {

    @Test
    void emptyListProducesFourUnconfiguredRowsInEnumOrder() {
        List<ApiKeyRow> rows = ApiKeyRows.from(List.of());
        assertEquals(4, rows.size());
        for (int i = 0; i < ApiKeySource.values().length; i++) {
            assertEquals(ApiKeySource.values()[i], rows.get(i).source());
            assertFalse(rows.get(i).configured());
        }
    }

    @Test
    void exactlyShodanEntryMarksShodanConfigured() {
        List<ApiKeyRow> rows = ApiKeyRows.from(List.of("apikey.shodan"));
        for (ApiKeyRow row : rows) {
            assertEquals(row.source() == ApiKeySource.SHODAN, row.configured());
        }
    }

    @Test
    void allFourEntryNamesMarkAllFourConfigured() {
        List<String> entryNames = List.of("apikey.virustotal", "apikey.shodan",
                "apikey.abuseipdb", "apikey.censys");
        List<ApiKeyRow> rows = ApiKeyRows.from(entryNames);
        for (ApiKeyRow row : rows) {
            assertTrue(row.configured());
        }
    }

    @Test
    void unknownNamesDoNotDropOrFalselyConfigureRows() {
        List<String> entryNames = List.of("apikey.greynoise", "note", "");
        List<ApiKeyRow> rows = ApiKeyRows.from(entryNames);
        assertEquals(4, rows.size());
        for (ApiKeyRow row : rows) {
            assertFalse(row.configured());
        }
    }

    @Test
    void prefixIsLoadBearingUnprefixedNameDoesNotCount() {
        List<ApiKeyRow> rows = ApiKeyRows.from(List.of("virustotal"));
        for (ApiKeyRow row : rows) {
            assertFalse(row.configured());
        }
    }

    @Test
    void nullThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ApiKeyRows.from(null));
    }

    @Test
    void returnedListIsImmutable() {
        List<ApiKeyRow> rows = ApiKeyRows.from(List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> rows.add(new ApiKeyRow(ApiKeySource.CENSYS, false)));
    }

    @Test
    void apiKeyRowHasExactlyTwoComponentsOfTypeSourceAndBoolean() {
        RecordComponent[] components = ApiKeyRow.class.getRecordComponents();
        assertEquals(2, components.length);
        assertEquals(ApiKeySource.class, components[0].getType());
        assertEquals(boolean.class, components[1].getType());
    }

    @Test
    void statusTextIsConfiguredOrNotConfiguredAndNeverAValue() {
        ApiKeyRow configured = new ApiKeyRow(ApiKeySource.VIRUSTOTAL, true);
        ApiKeyRow notConfigured = new ApiKeyRow(ApiKeySource.VIRUSTOTAL, false);
        assertEquals("configured", configured.statusText());
        assertEquals("not configured", notConfigured.statusText());
    }

    @Test
    void displayNameAndEntryNameDelegateToSource() {
        ApiKeyRow row = new ApiKeyRow(ApiKeySource.SHODAN, false);
        assertEquals(ApiKeySource.SHODAN.displayName(), row.displayName());
        assertEquals(ApiKeySource.SHODAN.entryName(), row.entryName());
    }
}
