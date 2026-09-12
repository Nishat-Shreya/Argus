package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.PortResult;
import com.argus.core.PortState;
import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 6.3: {@code FindingRows} — mapping {@code PortResult}/{@code Subdomain} to {@code FindingRow}. */
class FindingRowsTest {

    @Test
    void mapsAnOpenPortResult() {
        FindingRow row = FindingRows.of(new PortResult("example.com", 443, PortState.OPEN));

        assertEquals("port", row.type());
        assertEquals("example.com", row.subject());
        assertEquals("443", row.port());
        assertEquals("open", row.state());
    }

    @Test
    void mapsClosedAndFilteredStates() {
        FindingRow closed = FindingRows.of(new PortResult("h", 1, PortState.CLOSED));
        FindingRow filtered = FindingRows.of(new PortResult("h", 1, PortState.FILTERED));
        FindingRow open = FindingRows.of(new PortResult("h", 1, PortState.OPEN));

        assertEquals("closed", closed.state());
        assertEquals("filtered", filtered.state());
        assertEquals("open", open.state());
    }

    @Test
    void mapsASubdomain() {
        FindingRow row = FindingRows.of(new Subdomain("api.example.com"));

        assertEquals("subdomain", row.type());
        assertEquals("api.example.com", row.subject());
        assertEquals("", row.port());
        assertEquals("", row.state());
    }

    @Test
    void preservesTheWildcardPrefix() {
        FindingRow row = FindingRows.of(new Subdomain("*.example.com"));
        assertEquals("*.example.com", row.subject());
    }

    @Test
    void rejectsAnUnknownFindingType() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FindingRows.of("not a finding"));
        assertTrue(e.getMessage().contains("String"));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> FindingRows.of((Object) null));
    }

    @Test
    void batchMappingPreservesOrderAndSize() {
        List<Object> mixed = List.of(
                new Subdomain("api.example.com"),
                new PortResult("example.com", 443, PortState.OPEN),
                new Subdomain("mail.example.com"));

        List<FindingRow> rows = FindingRows.of(mixed);

        assertEquals(3, rows.size());
        assertEquals("api.example.com", rows.get(0).subject());
        assertEquals("example.com", rows.get(1).subject());
        assertEquals("mail.example.com", rows.get(2).subject());
    }

    @Test
    void batchResultIsUnmodifiable() {
        List<FindingRow> rows = FindingRows.of(List.of(new Subdomain("api.example.com")));
        assertThrows(UnsupportedOperationException.class,
                () -> rows.add(new FindingRow("x", "y", "", "")));
    }

    @Test
    void logLineFormats() {
        FindingRow port = FindingRows.of(new PortResult("example.com", 443, PortState.OPEN));
        FindingRow subdomain = FindingRows.of(new Subdomain("api.example.com"));

        assertTrue(port.logLine().contains("example.com:443"));
        assertTrue(port.logLine().contains("open"));
        assertTrue(subdomain.logLine().contains("api.example.com"));
        assertTrue(subdomain.logLine().contains("subdomain"));
    }

    @Test
    void batchMappingWorksWithAnArrayListToo() {
        List<Object> mixed = new ArrayList<>();
        mixed.add(new Subdomain("a.example.com"));
        List<FindingRow> rows = FindingRows.of(mixed);
        assertEquals(1, rows.size());
    }
}
