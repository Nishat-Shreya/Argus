package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Plan §6.1: the value type assigned by {@link TagDao#assign}. */
class NewTagTest {

    @Test
    void happyPathPreservesNameVerbatimIncludingInnerSpacesAndMixedCase() {
        NewTag tag = new NewTag("Prod Environment");
        assertEquals("Prod Environment", tag.name());
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new NewTag(null));
    }

    @Test
    void rejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> new NewTag(""));
    }

    @Test
    void rejectsWhitespaceOnlyName() {
        assertThrows(IllegalArgumentException.class, () -> new NewTag("   \t "));
    }

    @Test
    void recordsWithEqualComponentsAreEqual() {
        NewTag a = new NewTag("prod");
        NewTag b = new NewTag("prod");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void hasNoJavaTimeComponentAndSourceImportsNoJavaTimeType() throws IOException {
        for (RecordComponent component : NewTag.class.getRecordComponents()) {
            Package pkg = component.getType().getPackage();
            assertFalse(pkg != null && pkg.getName().startsWith("java.time"),
                    "NewTag must carry no time component, but " + component.getName()
                            + " is " + component.getType());
        }
        String source = Files.readString(Path.of("src/main/java/com/argus/db/NewTag.java"));
        assertFalse(source.contains("java.time"), "NewTag.java must not import java.time");
    }
}
