package com.argus.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads crt.sh test fixtures from the classpath. No filesystem paths (P1-01 R5 precedent). */
final class Fixtures {

    private Fixtures() {}

    /** Reads {@code /com/argus/core/crtsh/<name>} as UTF-8; fails loudly if absent. */
    static String read(String name) {
        return read("crtsh", name);
    }

    /** Reads {@code /com/argus/core/<source>/<name>} as UTF-8; fails loudly if absent. */
    static String read(String source, String name) {
        String path = "/com/argus/core/" + source + "/" + name;
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read test fixture: " + path, e);
        }
    }
}
