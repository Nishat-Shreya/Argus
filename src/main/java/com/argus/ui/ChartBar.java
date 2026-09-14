package com.argus.ui;

import java.util.Objects;

/**
 * One stacked-bar datum: a distinct {@code (port, state)} pair and its count (plan §4.2). Pure,
 * toolkit-free.
 */
record ChartBar(int port, String state, int count) {

    ChartBar {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535, was " + port);
        }
        Objects.requireNonNull(state, "state");
        if (state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
    }

    /** {@code String.valueOf(port)} -- the CategoryAxis key. */
    String category() {
        return String.valueOf(port);
    }

    String styleClass() {
        return ChartData.styleClassFor(state);
    }
}
