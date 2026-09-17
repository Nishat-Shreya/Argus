package com.argus.ui;

import com.argus.core.FindingTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * THE tag filter decision: which loaded rows survive the current filter choice (plan §3.3).
 * Pure, toolkit-free, single-purpose — deliberately NOT folded into {@link Tags}, which owns
 * wording only.
 */
final class TagFilter {

    private TagFilter() {
    }

    /** Every row when {@code selectedTagName} is null or {@link Tags#ALL_TAGS}; otherwise the
     *  rows whose findingId carries a tag with exactly that name. Input order is preserved. */
    static List<NoteRow> apply(List<NoteRow> rows, Map<Long, List<FindingTag>> tagsByFinding,
            String selectedTagName) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(tagsByFinding, "tagsByFinding");

        if (Tags.isAllTags(selectedTagName)) {
            return List.copyOf(rows);
        }

        List<NoteRow> matched = new ArrayList<>();
        for (NoteRow row : rows) {
            List<FindingTag> findingTags = tagsByFinding.get(row.findingId());
            if (findingTags == null) {
                continue;
            }
            for (FindingTag tag : findingTags) {
                if (tag.name().equals(selectedTagName)) {
                    matched.add(row);
                    break;
                }
            }
        }
        return List.copyOf(matched);
    }
}
