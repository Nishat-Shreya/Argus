package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.db.FindingRecord;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

/**
 * Reflective pin on {@link com.argus.db.FindingRecord} (plan §5 step 1 / §7.1): exactly 6
 * record components, named exactly {@code id, scanId, type, subject, port, state}. This is
 * the tripwire for the claim that {@code state} is the only comparable attribute a diff can
 * report as "changed" — if someone adds a seventh component to {@code FindingRecord}, this test
 * fails and forces a decision about whether the new field is identity, attribute, or ignored,
 * rather than letting {@link ScanDiffEngine} silently miss it.
 */
class FindingRecordShapeTest {

    @Test
    void findingRecordHasExactlySixComponentsWithTheExpectedNames() {
        RecordComponent[] components = FindingRecord.class.getRecordComponents();
        assertEquals(6, components.length,
                "FindingRecord must have exactly 6 record components");

        String[] expectedNames = {"id", "scanId", "type", "subject", "port", "state"};
        for (int i = 0; i < expectedNames.length; i++) {
            assertEquals(expectedNames[i], components[i].getName(),
                    "component " + i + " must be named " + expectedNames[i]);
        }
    }
}
