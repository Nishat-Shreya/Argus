package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.FindingType;
import com.argus.db.NewFinding;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T4's driver: {@link NewFindings} — THE core -&gt; db finding mapping (P1-04 §7.1). */
class NewFindingsTest {

    @Test
    void mapsAnOpenPortResultWithUppercaseStateToken() {
        NewFinding finding = NewFindings.of(new PortResult("example.com", 443, PortState.OPEN));

        assertEquals(FindingType.PORT, finding.type());
        assertEquals("example.com", finding.subject());
        assertEquals(443, finding.port());
        assertEquals("OPEN", finding.state());
    }

    @Test
    void mapsClosedAndFilteredStatesVerbatim() {
        NewFinding closed = NewFindings.of(new PortResult("h", 1, PortState.CLOSED));
        NewFinding filtered = NewFindings.of(new PortResult("h", 1, PortState.FILTERED));

        assertEquals("CLOSED", closed.state());
        assertEquals("FILTERED", filtered.state());
    }

    @Test
    void mapsASubdomain() {
        NewFinding finding = NewFindings.of(new Subdomain("api.example.com"));

        assertEquals(FindingType.SUBDOMAIN, finding.type());
        assertEquals("api.example.com", finding.subject());
        assertNull(finding.port());
        assertNull(finding.state());
    }

    @Test
    void preservesTheWildcardPrefix() {
        NewFinding finding = NewFindings.of(new Subdomain("*.example.com"));
        assertEquals("*.example.com", finding.subject());
    }

    @Test
    void rejectsAnUnknownFindingType() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NewFindings.of("not a finding"));
        assertTrue(e.getMessage().contains("String"));

        IllegalArgumentException e2 =
                assertThrows(IllegalArgumentException.class, () -> NewFindings.of(42));
        assertTrue(e2.getMessage().contains("Integer"));
    }

    @Test
    void rejectsNullElement() {
        assertThrows(NullPointerException.class, () -> NewFindings.of((Object) null));
    }

    @Test
    void rejectsNullCollection() {
        assertThrows(NullPointerException.class, () -> NewFindings.of((List<?>) null));
    }

    @Test
    void emptyCollectionIsNotAnError() {
        List<NewFinding> result = NewFindings.of(List.of());
        assertEquals(List.of(), result);
    }

    @Test
    void mixedCollectionPreservesOrderAndIsUnmodifiable() {
        List<Object> mixed = List.of(
                new Subdomain("api.example.com"),
                new PortResult("example.com", 443, PortState.OPEN),
                new Subdomain("mail.example.com"));

        List<NewFinding> result = NewFindings.of(mixed);

        assertEquals(3, result.size());
        assertEquals("api.example.com", result.get(0).subject());
        assertEquals("example.com", result.get(1).subject());
        assertEquals("mail.example.com", result.get(2).subject());
        assertThrows(UnsupportedOperationException.class,
                () -> result.add(NewFindings.of(new Subdomain("x.example.com"))));
    }

    @Test
    void batchMappingWorksWithAnArrayListToo() {
        List<Object> mixed = new ArrayList<>();
        mixed.add(new Subdomain("a.example.com"));
        List<NewFinding> result = NewFindings.of(mixed);
        assertEquals(1, result.size());
    }
}
