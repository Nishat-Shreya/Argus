package com.argus.ui;

import java.util.Locale;
import java.util.Objects;

/**
 * One pie-chart datum: a distinct port-probe state token and how many probes carried it (plan
 * §4.2). Pure, toolkit-free.
 */
record ChartSlice(String state, int count) {

    ChartSlice {
        Objects.requireNonNull(state, "state");
        if (state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
    }

    /** {@code "open (3)"} -- lowercase(Locale.ROOT) token + " (" + count + ")". */
    String label() {
        return state.toLowerCase(Locale.ROOT) + " (" + count + ")";
    }

    String styleClass() {
        return ChartData.styleClassFor(state);
    }
}
