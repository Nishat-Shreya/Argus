package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The structural guard for §0.3.2 of the plan: reflects over the six boundary-projection
 * records and asserts that no record component's type, and no type argument of a component's
 * generic type, has a package name starting {@code com.argus.db}. This is the inference hole
 * that a plain import scan cannot catch — it fails if someone later "simplifies"
 * {@code ScanDiffReport} by re-typing a list as {@code List<FindingRecord>}.
 *
 * {@code FindingNote} joins the list in P3-06 (plan R7): a genuine gap being closed, not a
 * convenience edit — a new public {@code core} record on the {@code ui} boundary that was not in
 * this list is exactly the inference hole this test exists to close.
 */
class ProjectionBoundaryTest {

    private static final List<Class<?>> PROJECTION_TYPES = List.of(
            ScanSummary.class, FindingSnapshot.class, FindingDelta.class, ScanDiffReport.class,
            ScanComparison.class, FindingNote.class);

    @Test
    void noRecordComponentNamesADbType() {
        for (Class<?> type : PROJECTION_TYPES) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertFalse(isOrReferencesDbType(component.getGenericType()),
                        type.getSimpleName() + "." + component.getName()
                                + " must not name a com.argus.db type: "
                                + component.getGenericType());
            }
        }
    }

    @Test
    void noGenericTypeArgumentNamesADbType() {
        for (Class<?> type : PROJECTION_TYPES) {
            for (RecordComponent component : type.getRecordComponents()) {
                Type generic = component.getGenericType();
                if (generic instanceof ParameterizedType parameterized) {
                    for (Type argument : parameterized.getActualTypeArguments()) {
                        assertFalse(isOrReferencesDbType(argument),
                                type.getSimpleName() + "." + component.getName()
                                        + " has a type argument in com.argus.db: " + argument);
                    }
                }
            }
        }
    }

    @Test
    void allSixProjectionTypesAreCovered() {
        assertEquals(6, PROJECTION_TYPES.size());
    }

    @Test
    void findingSnapshotHasExactlyFiveComponentsAndNoneIsAJavaTimeType() {
        RecordComponent[] components = FindingSnapshot.class.getRecordComponents();
        assertEquals(5, components.length);
        for (RecordComponent component : components) {
            Package pkg = component.getType().getPackage();
            assertFalse(pkg != null && pkg.getName().startsWith("java.time"),
                    "FindingSnapshot must carry no per-finding timestamp, but "
                            + component.getName() + " is " + component.getType());
        }
    }

    /** Recursively checks a {@link Type} and any nested type arguments for a {@code com.argus.db}
     *  package name. */
    private static boolean isOrReferencesDbType(Type type) {
        if (type instanceof Class<?> clazz) {
            Package pkg = clazz.getPackage();
            if (pkg != null && pkg.getName().startsWith("com.argus.db")) {
                return true;
            }
            return false;
        }
        if (type instanceof ParameterizedType parameterized) {
            if (isOrReferencesDbType(parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (isOrReferencesDbType(argument)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
