package com.argus.ui;

import java.nio.file.Path;
import java.util.List;

/**
 * An immutable, toolkit-free snapshot of a Dragboard's contents, taken on the FX thread INSIDE
 * the DRAG_DROPPED handler and BEFORE {@code DragEvent.setDropCompleted(...)} -- the javadoc
 * states "No dragboard access can happen after this call". Holding {@link java.nio.file.Path}
 * (not {@code java.io.File}) keeps every consumer javafx-free and awt-free (plan §3.1).
 */
record DroppedContent(boolean hasFiles, List<Path> files, boolean hasText, String text) {

    DroppedContent {
        files = files == null ? List.of() : List.copyOf(files);
        text = text == null ? "" : text;
    }

    static DroppedContent none() {
        return new DroppedContent(false, List.of(), false, "");
    }

    static DroppedContent ofFiles(List<Path> files) {
        return new DroppedContent(true, files, false, "");
    }

    static DroppedContent ofText(String text) {
        return new DroppedContent(false, List.of(), true, text);
    }

    /** True when the drop carries neither a usable file nor non-blank text. */
    boolean isEmpty() {
        return files.isEmpty() && text.isBlank();
    }
}
