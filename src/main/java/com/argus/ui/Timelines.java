package com.argus.ui;

import com.argus.core.ScanSummary;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Every axis rule for the timeline screen (plan §3.3): filtering, ordering, the
 * {@code MAX_POINTS} cap, index math and label text. Pure, toolkit-free. {@code ZoneId} is
 * passed in, never read here — the {@code ScanChoices} contract, so this class is testable at a
 * fixed zone and reads no environment.
 *
 * <p>Completeness is not re-implemented: {@link #targets} and {@link #track} both call
 * {@link ScanSummary#complete()}, the same single authority {@code ScanChoices.selectable}
 * calls. {@code ScanChoices.selectable}/{@code dates}/{@code onDate} are deliberately not used
 * here: {@code ScanChoice} drops {@code target} and the date half of the timestamp, both of
 * which an axis needs. This is a different projection, not a forked rule.
 */
final class Timelines {

    static final int MAX_POINTS = 30;

    private static final DateTimeFormatter LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Timelines() {
    }

    /** Distinct targets among {@code complete()} scans only, alphabetical (Locale.ROOT),
     *  verbatim strings. */
    static List<String> targets(List<ScanSummary> scans) {
        Objects.requireNonNull(scans, "scans");
        TreeSet<String> targets = new TreeSet<>();
        for (ScanSummary scan : scans) {
            if (scan.complete()) {
                targets.add(scan.target());
            }
        }
        return List.copyOf(targets);
    }

    /** {@code COMPLETE} scans whose {@code target().equals(target)}, sorted by
     *  {@code startedAt} ascending then id ascending, keeping the most recent
     *  {@code MAX_POINTS}, projected to {@code TimelinePoint} oldest-first. */
    static TimelineTrack track(List<ScanSummary> scans, String target, ZoneId zone) {
        Objects.requireNonNull(scans, "scans");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(zone, "zone");

        List<ScanSummary> matching = new ArrayList<>();
        for (ScanSummary scan : scans) {
            if (scan.complete() && scan.target().equals(target)) {
                matching.add(scan);
            }
        }
        matching.sort((a, b) -> {
            int byStart = a.startedAt().compareTo(b.startedAt());
            if (byStart != 0) {
                return byStart;
            }
            return Long.compare(a.id(), b.id());
        });

        int total = matching.size();
        List<ScanSummary> kept = total > MAX_POINTS
                ? matching.subList(total - MAX_POINTS, total)
                : matching;

        List<TimelinePoint> points = new ArrayList<>();
        for (ScanSummary scan : kept) {
            String label = LABEL_FORMAT.format(scan.startedAt().atZone(zone));
            points.add(new TimelinePoint(scan.id(), scan.target(), label));
        }

        return new TimelineTrack(target, points, total);
    }

    /** {@code pointCount <= 0 -> -1}; else {@code clamp(round(sliderValue), 0, pointCount - 1)}. */
    static int indexFor(double sliderValue, int pointCount) {
        if (pointCount <= 0) {
            return -1;
        }
        long rounded = Math.round(sliderValue);
        if (rounded < 0) {
            return 0;
        }
        if (rounded > pointCount - 1) {
            return pointCount - 1;
        }
        return (int) rounded;
    }

    /** {@code "scan 4 of 9 · 2026-09-14 14:32:07"}; {@code ""} when the track is empty or
     *  {@code index} is out of range. */
    static String positionLabel(TimelineTrack track, int index) {
        Objects.requireNonNull(track, "track");
        if (track.isEmpty() || index < 0 || index >= track.size()) {
            return "";
        }
        return "scan " + (index + 1) + " of " + track.size() + " · " + track.point(index).label();
    }

    /** {@code ""} when not truncated, else {@code "showing the 30 most recent of 57 scans for
     *  example.com"}. */
    static String truncationNote(TimelineTrack track) {
        Objects.requireNonNull(track, "track");
        if (!track.truncated()) {
            return "";
        }
        return "showing the " + track.size() + " most recent of " + track.totalForTarget()
                + " scans for " + track.target();
    }

    /** {@code ""} when {@code hiddenCount == 0}, else {@code "3 scans hidden — cancelled or
     *  failed scans are partial and are not on the timeline"}. */
    static String hiddenNote(int hiddenCount) {
        if (hiddenCount == 0) {
            return "";
        }
        return hiddenCount + " scans hidden — cancelled or failed scans are partial and are not "
                + "on the timeline";
    }
}
