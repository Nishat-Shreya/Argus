package com.argus.ui;

import com.argus.core.FindingTag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Every wording, grouping and choice-list rule for tags on the findings-detail screen (plan
 * §3.3). Pure, toolkit-free. The {@code Notes} twin — but {@code Tags} takes no time-zone
 * parameter of any kind: a tag has no time concept at all (plan §0.2).
 */
final class Tags {

    static final String ALL_TAGS = "all tags";

    private Tags() {
    }

    /** Groups by {@code findingId}, preserving each finding's tag order. Immutable. */
    static Map<Long, List<FindingTag>> byFinding(List<FindingTag> tags) {
        Objects.requireNonNull(tags, "tags");
        Map<Long, List<FindingTag>> grouped = new LinkedHashMap<>();
        for (FindingTag tag : tags) {
            grouped.computeIfAbsent(tag.findingId(), key -> new ArrayList<>()).add(tag);
        }
        Map<Long, List<FindingTag>> immutable = new LinkedHashMap<>();
        for (Map.Entry<Long, List<FindingTag>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    /** {@code ALL_TAGS} first, then distinct names, case-insensitively sorted. */
    static List<String> filterChoices(List<FindingTag> scanTags) {
        Objects.requireNonNull(scanTags, "scanTags");
        TreeSet<String> distinctNames =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
        for (FindingTag tag : scanTags) {
            distinctNames.add(tag.name());
        }
        List<String> choices = new ArrayList<>();
        choices.add(ALL_TAGS);
        choices.addAll(distinctNames);
        return List.copyOf(choices);
    }

    /** {@code current} if still present among {@code choices}, else {@link #ALL_TAGS}. */
    static String preservedSelection(List<String> choices, String current) {
        Objects.requireNonNull(choices, "choices");
        if (current != null && choices.contains(current)) {
            return current;
        }
        return ALL_TAGS;
    }

    /** True for {@code null} and for {@link #ALL_TAGS}. */
    static boolean isAllTags(String choice) {
        return choice == null || ALL_TAGS.equals(choice);
    }

    /** {@code "" | "1 tag" | "N tags"}. */
    static String tagCountLabel(int count) {
        if (count == 0) {
            return "";
        }
        if (count == 1) {
            return "1 tag";
        }
        return count + " tags";
    }

    static String emptyTagsText() {
        return "no tags on this finding yet";
    }

    static String noMatchesText(String tagName) {
        return "no findings tagged \"" + tagName + "\" in this scan";
    }
}
