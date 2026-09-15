package com.argus.ui;

import com.argus.core.ScanCompletion;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pending scan-target queue.
 *
 * THREAD CONFINEMENT: every instance method is called ONLY from the JavaFX Application Thread.
 * It holds no lock, no volatile field, no atomic and no concurrent collection, and it needs
 * none. THIS IS NOT THE PRODUCER-CONSUMER PIPELINE OF INVARIANT 4 -- no scan thread ever touches
 * it; {@code ScanPipeline}, {@code PauseGate} and {@code ScanCoordinator} are untouched by this
 * item. A reviewer must not read the word "queue" here as an invariant-4 obligation (plan §4.1).
 */
final class TargetQueue {

    static final int MAX_PENDING = 50;

    private final LinkedHashSet<String> pending = new LinkedHashSet<>();

    /**
     * @param activeTarget the currently-running scan's normalized target, or null when idle;
     *                      it occupies a slot so a re-drop of the running target is a duplicate
     */
    QueueAdmission admit(TargetDrop drop, String activeTarget) {
        Objects.requireNonNull(drop, "drop");

        List<String> queued = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        int overflow = 0;

        for (String target : drop.accepted()) {
            if (target.equals(activeTarget) || pending.contains(target)) {
                duplicates.add(target);
                continue;
            }
            if (pending.size() >= MAX_PENDING) {
                overflow++;
                continue;
            }
            pending.add(target);
            queued.add(target);
        }

        return new QueueAdmission(queued, duplicates, drop.rejected(), overflow);
    }

    Optional<String> poll() {
        Iterator<String> iterator = pending.iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        String first = iterator.next();
        iterator.remove();
        return Optional.of(first);
    }

    boolean remove(String target) {
        return pending.remove(target);
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int size() {
        return pending.size();
    }

    /** Unmodifiable snapshot, queue order. */
    List<String> pending() {
        return List.copyOf(pending);
    }

    /**
     * Whether the queue advances to the next target after a scan ended this way. Exhaustive
     * switch with NO default: {@code COMPLETED} -&gt; true, {@code COMPLETED_WITH_ERRORS} -&gt;
     * true, {@code CANCELLED} -&gt; false (plan §0.3). A future {@code ScanCompletion} constant
     * must fail to compile here.
     */
    static boolean advancesAfter(ScanCompletion completion) {
        return switch (completion) {
            case COMPLETED -> true;
            case COMPLETED_WITH_ERRORS -> true;
            case CANCELLED -> false;
        };
    }
}
