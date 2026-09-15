package com.argus.ui;

import java.util.ArrayList;
import java.util.List;

/** What actually happened when a {@link TargetDrop} was offered to the queue (plan §3.3). */
record QueueAdmission(List<String> queued, List<String> duplicates, List<String> rejected,
        int overflow) {

    QueueAdmission {
        queued = queued == null ? List.of() : List.copyOf(queued);
        duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    static QueueAdmission nothing() {
        return new QueueAdmission(List.of(), List.of(), List.of(), 0);
    }

    /**
     * One honest sentence for the queue status label AND the log console -- the single wording
     * authority for this screen (the {@code Reports.hiddenNote} precedent: wording here,
     * counting in the records it reads).
     */
    String describe() {
        if (queued.isEmpty() && duplicates.isEmpty() && rejected.isEmpty() && overflow == 0) {
            return "nothing to queue — no valid domain in the drop";
        }

        List<String> segments = new ArrayList<>();
        segments.add("queued " + queued.size());
        if (!duplicates.isEmpty()) {
            segments.add(duplicates.size() + " already queued");
        }
        if (!rejected.isEmpty()) {
            boolean truncated = rejected.size() > DroppedTargets.MAX_REJECTED_SAMPLES;
            List<String> samples = truncated
                    ? rejected.subList(0, DroppedTargets.MAX_REJECTED_SAMPLES)
                    : rejected;
            String names = String.join(", ", samples) + (truncated ? " …" : "");
            segments.add(rejected.size() + " not a valid domain (" + names + ")");
        }
        if (overflow > 0) {
            segments.add(overflow + " over the queue limit");
        }
        return String.join(" · ", segments);
    }
}
