package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan §6.5: {@link TagFilter}, the tag filter decision. */
class TagFilterTest {

    private static final NoteRow ROW_A = new NoteRow(1L, "port", "a.example.com", "80", "open");
    private static final NoteRow ROW_B = new NoteRow(2L, "port", "b.example.com", "443", "open");
    private static final NoteRow ROW_C = new NoteRow(3L, "subdomain", "c.example.com", "", "");

    @Test
    void nullOrAllTagsReturnsAllRowsInInputOrder() {
        List<NoteRow> rows = List.of(ROW_A, ROW_B, ROW_C);
        Map<Long, List<FindingTag>> tags = Map.of(1L, List.of(new FindingTag(1L, 1L, "prod")));

        assertEquals(rows, TagFilter.apply(rows, tags, null));
        assertEquals(rows, TagFilter.apply(rows, tags, Tags.ALL_TAGS));
    }

    @Test
    void namedFilterReturnsOnlyRowsCarryingThatTagInInputOrder() {
        List<NoteRow> rows = List.of(ROW_A, ROW_B, ROW_C);
        Map<Long, List<FindingTag>> tags = Map.of(
                1L, List.of(new FindingTag(1L, 1L, "prod")),
                2L, List.of(new FindingTag(2L, 2L, "prod")));

        assertEquals(List.of(ROW_A, ROW_B), TagFilter.apply(rows, tags, "prod"));
    }

    @Test
    void aFindingAbsentFromTheMapIsExcludedByANameFilterButIncludedByAllTags() {
        List<NoteRow> rows = List.of(ROW_A, ROW_C);
        Map<Long, List<FindingTag>> tags = Map.of(1L, List.of(new FindingTag(1L, 1L, "prod")));

        assertEquals(List.of(ROW_A), TagFilter.apply(rows, tags, "prod"));
        assertEquals(rows, TagFilter.apply(rows, tags, Tags.ALL_TAGS));
    }

    @Test
    void aFindingCarryingTwoTagsMatchesEitherName() {
        List<NoteRow> rows = List.of(ROW_A);
        Map<Long, List<FindingTag>> tags = Map.of(1L, List.of(
                new FindingTag(1L, 1L, "prod"), new FindingTag(2L, 1L, "external")));

        assertEquals(List.of(ROW_A), TagFilter.apply(rows, tags, "prod"));
        assertEquals(List.of(ROW_A), TagFilter.apply(rows, tags, "external"));
    }

    @Test
    void emptyRowsReturnsEmptyAndEmptyMapWithANameFilterReturnsEmpty() {
        assertTrue(TagFilter.apply(List.of(), Map.of(), "prod").isEmpty());
        assertTrue(TagFilter.apply(List.of(ROW_A), Map.of(), "prod").isEmpty());
    }

    @Test
    void filteringByANameThatExistsOnNoFindingIsAnEmptyListNotAnException() {
        List<NoteRow> rows = List.of(ROW_A, ROW_B);
        Map<Long, List<FindingTag>> tags = Map.of(1L, List.of(new FindingTag(1L, 1L, "prod")));

        assertEquals(List.of(), TagFilter.apply(rows, tags, "nonexistent"));
    }

    @Test
    void returnedListIsImmutableAndInputListIsNotMutated() {
        List<NoteRow> rows = new ArrayList<>(List.of(ROW_A, ROW_B));
        Map<Long, List<FindingTag>> tags = Map.of(1L, List.of(new FindingTag(1L, 1L, "prod")));

        List<NoteRow> filtered = TagFilter.apply(rows, tags, "prod");
        assertThrows(UnsupportedOperationException.class, () -> filtered.add(ROW_C));
        assertEquals(2, rows.size());
    }
}
