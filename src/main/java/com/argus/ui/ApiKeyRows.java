package com.argus.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Maps {@link com.argus.core.Vault#keys()}' output onto {@link ApiKeyRow}. Pure, toolkit-free.
 * Takes the vault's entry-name list, never the {@code Vault} itself: this function cannot read
 * a value even by accident, and is testable with a {@code List.of(...)}.
 */
final class ApiKeyRows {

    private ApiKeyRows() {
    }

    /**
     * One row per catalogue entry, in enum declaration order, marked configured iff the
     * vault's entry-name list contains that row's entry name.
     *
     * @throws NullPointerException if vaultEntryNames is null
     * @return an immutable list, always ApiKeySource.values().length long
     */
    static List<ApiKeyRow> from(Collection<String> vaultEntryNames) {
        Objects.requireNonNull(vaultEntryNames, "vaultEntryNames");
        List<ApiKeyRow> rows = new ArrayList<>(ApiKeySource.values().length);
        for (ApiKeySource source : ApiKeySource.values()) {
            boolean configured = vaultEntryNames.contains(source.entryName());
            rows.add(new ApiKeyRow(source, configured));
        }
        return List.copyOf(rows);
    }
}
