package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingTag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan §6.5: {@link Tags}, every wording/grouping/choice-list rule for the tag feature. */
class TagsTest {

    @Test
    void byFindingGroupsAndPreservesOrderAndIsImmutable() {
        FindingTag t1 = new FindingTag(1L, 10L, "prod");
        FindingTag t2 = new FindingTag(2L, 10L, "external");
        FindingTag t3 = new FindingTag(3L, 20L, "aws");
        Map<Long, List<FindingTag>> grouped = Tags.byFinding(List.of(t1, t2, t3));

        assertEquals(2, grouped.size());
        assertEquals(List.of(t1, t2), grouped.get(10L));
        assertEquals(List.of(t3), grouped.get(20L));
        assertThrows(UnsupportedOperationException.class,
                () -> grouped.put(30L, List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> grouped.get(10L).add(t3));
    }

    @Test
    void byFindingOnEmptyInputReturnsAnEmptyMap() {
        assertTrue(Tags.byFinding(List.of()).isEmpty());
    }

    @Test
    void filterChoicesPutsAllTagsFirstThenDistinctNamesCaseInsensitivelySorted() {
        FindingTag t1 = new FindingTag(1L, 10L, "Web");
        FindingTag t2 = new FindingTag(2L, 10L, "aws");
        List<String> choices = Tags.filterChoices(List.of(t1, t2));

        assertEquals(List.of(Tags.ALL_TAGS, "aws", "Web"), choices);
    }

    @Test
    void filterChoicesOnEmptyInputYieldsOnlyAllTags() {
        assertEquals(List.of(Tags.ALL_TAGS), Tags.filterChoices(List.of()));
    }

    @Test
    void filterChoicesDeduplicatesANameAppearingOnManyFindings() {
        FindingTag t1 = new FindingTag(1L, 10L, "prod");
        FindingTag t2 = new FindingTag(1L, 20L, "prod");
        List<String> choices = Tags.filterChoices(List.of(t1, t2));

        assertEquals(List.of(Tags.ALL_TAGS, "prod"), choices);
    }

    @Test
    void preservedSelectionKeepsCurrentWhenStillPresentElseFallsBackToAllTags() {
        List<String> choices = List.of(Tags.ALL_TAGS, "aws", "prod");
        assertEquals("prod", Tags.preservedSelection(choices, "prod"));
        assertEquals(Tags.ALL_TAGS, Tags.preservedSelection(choices, "gone"));
        assertEquals(Tags.ALL_TAGS, Tags.preservedSelection(choices, null));
    }

    @Test
    void isAllTagsIsTrueForNullAndTheSentinelAndFalseOtherwise() {
        assertTrue(Tags.isAllTags(null));
        assertTrue(Tags.isAllTags(Tags.ALL_TAGS));
        assertFalse(Tags.isAllTags("prod"));
    }

    @Test
    void tagCountLabelExactText() {
        assertEquals("", Tags.tagCountLabel(0));
        assertEquals("1 tag", Tags.tagCountLabel(1));
        assertEquals("4 tags", Tags.tagCountLabel(4));
    }

    @Test
    void noMatchesTextAndEmptyTagsTextExactText() {
        assertEquals("no findings tagged \"prod\" in this scan", Tags.noMatchesText("prod"));
        assertEquals("no tags on this finding yet", Tags.emptyTagsText());
    }

    @Test
    void readsNoClockAndNoZone() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/argus/ui/Tags.java"));
        assertFalse(source.contains("ZoneId"), "Tags.java must not import or use ZoneId");
        assertFalse(source.contains("java.time"), "Tags.java must not import java.time");
    }
}
