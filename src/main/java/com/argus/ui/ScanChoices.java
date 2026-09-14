package com.argus.ui;

import com.argus.core.ScanSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Groups and filters {@link ScanSummary}s for the two {@code DatePicker}/{@code ComboBox} pairs.
 * Pure, toolkit-free (plan §3.5, §4.1, §4.4). {@code ZoneId} is passed explicitly by the caller
 * ({@code ScanDiffController}, which reads {@code ZoneId.systemDefault()} once) — this class
 * never reads a clock or a zone itself.
 */
final class ScanChoices {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ScanChoices() {
    }

    /** Only {@code complete()} summaries, in the input's order (newest-first, per
     *  {@code ScanHistory.listScans()}'s contract). */
    static List<ScanChoice> selectable(List<ScanSummary> scans, ZoneId zone) {
        Objects.requireNonNull(scans, "scans");
        Objects.requireNonNull(zone, "zone");
        List<ScanChoice> choices = new ArrayList<>();
        for (ScanSummary scan : scans) {
            if (scan.complete()) {
                choices.add(toChoice(scan, zone));
            }
        }
        return List.copyOf(choices);
    }

    /** Distinct dates, newest-first, in the order they first appear in {@code choices}. */
    static List<LocalDate> dates(List<ScanChoice> choices) {
        Objects.requireNonNull(choices, "choices");
        Set<LocalDate> seen = new LinkedHashSet<>();
        for (ScanChoice choice : choices) {
            seen.add(choice.date());
        }
        return List.copyOf(seen);
    }

    /** Every choice on {@code date}, preserving {@code choices}' order. */
    static List<ScanChoice> onDate(List<ScanChoice> choices, LocalDate date) {
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(date, "date");
        List<ScanChoice> onDate = new ArrayList<>();
        for (ScanChoice choice : choices) {
            if (choice.date().equals(date)) {
                onDate.add(choice);
            }
        }
        return List.copyOf(onDate);
    }

    /** Non-{@code complete()} scans — excluded from every picker, but counted, not vanished
     *  (plan §4.2). */
    static int hiddenCount(List<ScanSummary> scans) {
        Objects.requireNonNull(scans, "scans");
        int count = 0;
        for (ScanSummary scan : scans) {
            if (!scan.complete()) {
                count++;
            }
        }
        return count;
    }

    /** {@code ""} when nothing is hidden, else the exact text {@code hiddenLabel} shows
     *  (plan §4.2). */
    static String hiddenNote(List<ScanSummary> scans) {
        int hidden = hiddenCount(scans);
        if (hidden == 0) {
            return "";
        }
        return hidden + " scans hidden — cancelled or failed scans are partial and cannot be "
                + "compared";
    }

    private static ScanChoice toChoice(ScanSummary scan, ZoneId zone) {
        var zoned = scan.startedAt().atZone(zone);
        String label = TIME_FORMAT.format(zoned) + " · " + scan.target();
        return new ScanChoice(scan.id(), zoned.toLocalDate(), label);
    }
}
