package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.FindingTagRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan §3.2: THE db/core tag mapping. */
class FindingTagsTest {

    @Test
    void mapsFieldForField() {
        FindingTagRecord record = new FindingTagRecord(2L, 1L, "prod");
        FindingTag tag = FindingTags.of(record);
        assertEquals(1L, tag.tagId());
        assertEquals(2L, tag.findingId());
        assertEquals("prod", tag.name());
    }

    @Test
    void mapsAListPreservingOrder() {
        List<FindingTagRecord> records = List.of(
                new FindingTagRecord(2L, 1L, "prod"),
                new FindingTagRecord(2L, 3L, "external"));
        List<FindingTag> tags = FindingTags.of(records);
        assertEquals(2, tags.size());
        assertEquals("prod", tags.get(0).name());
        assertEquals("external", tags.get(1).name());
    }

    @Test
    void rejectsNullRecord() {
        assertThrows(NullPointerException.class, () -> FindingTags.of((FindingTagRecord) null));
    }

    @Test
    void rejectsNullList() {
        assertThrows(NullPointerException.class, () -> FindingTags.of((List<FindingTagRecord>) null));
    }

    @Test
    void mappedListIsImmutable() {
        List<FindingTag> tags = FindingTags.of(List.of(new FindingTagRecord(2L, 1L, "prod")));
        assertThrows(UnsupportedOperationException.class,
                () -> tags.add(new FindingTag(9L, 9L, "x")));
        assertTrue(tags.size() == 1);
    }
}
