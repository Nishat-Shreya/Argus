package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Plan §3.2: the projection record. */
class FindingTagTest {

    @Test
    void componentsArePreserved() {
        FindingTag tag = new FindingTag(1L, 2L, "prod");
        assertEquals(1L, tag.tagId());
        assertEquals(2L, tag.findingId());
        assertEquals("prod", tag.name());
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new FindingTag(1L, 2L, null));
    }

    @Test
    void hasNoJavaTimeComponentAndSourceImportsNoJavaTimeType() throws IOException {
        for (RecordComponent component : FindingTag.class.getRecordComponents()) {
            Package pkg = component.getType().getPackage();
            assertFalse(pkg != null && pkg.getName().startsWith("java.time"),
                    "FindingTag must carry no time component, but " + component.getName()
                            + " is " + component.getType());
        }
        String source = Files.readString(Path.of("src/main/java/com/argus/core/FindingTag.java"));
        assertFalse(source.contains("java.time"), "FindingTag.java must not import java.time");
    }
}
