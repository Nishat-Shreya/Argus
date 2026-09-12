package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.OperatorId;
import com.argus.core.VaultStore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Section 6.6: {@code LoginValidation} -- the invariant-8 seam. NO {@code javafx.*} imports,
 * so this runs headless today, the same trick {@code ThemeResourceTest} uses.
 */
class LoginValidationTest {

    @Test
    void acceptsAValidIdAndPassword() {
        LoginValidation.Result result = LoginValidation.check("nishat", 12, false);

        assertTrue(result.valid());
        assertEquals(OperatorId.of("nishat"), result.operatorId());
    }

    @Test
    void rejectsAnEmptyOperatorId() {
        LoginValidation.Result result = LoginValidation.check("", 12, false);

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("operator id"));
        assertTrue(Character.isUpperCase(result.message().charAt(0)));
        assertNull(result.operatorId());
    }

    @Test
    void rejectsAMalformedOperatorId() {
        LoginValidation.Result result = LoginValidation.check("a", 12, false);

        assertFalse(result.valid());
        assertFalse(OperatorId.isValid("a"));
        assertNull(result.operatorId());
    }

    @Test
    void rejectsAnEmptyPassword() {
        LoginValidation.Result result = LoginValidation.check("nishat", 0, false);

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("password"));
    }

    @Test
    void rejectsAShortPasswordOnlyWhenCreating() {
        LoginValidation.Result whenCreating = LoginValidation.check("nishat", 7, true);
        LoginValidation.Result whenOpening = LoginValidation.check("nishat", 7, false);

        assertFalse(whenCreating.valid());
        assertTrue(whenOpening.valid());
    }

    @Test
    void usesTheCoreConstantForTheLengthRule() {
        LoginValidation.Result result = LoginValidation.check("nishat", 7, true);

        assertTrue(result.message().contains(String.valueOf(VaultStore.MIN_MASTER_PASSWORD_LENGTH)));
    }

    @Test
    void validationTakesALengthNotThePassword() throws NoSuchMethodException {
        Method check = LoginValidation.class.getDeclaredMethod(
                "check", String.class, int.class, boolean.class);

        for (Class<?> paramType : check.getParameterTypes()) {
            assertFalse(paramType == char[].class);
            assertFalse(paramType == CharSequence.class);
        }
    }

    @Test
    void messagesNeverEchoInput() {
        String operatorId = "very$distinctive#operator!id";
        LoginValidation.Result badId = LoginValidation.check(operatorId, 12, false);
        assertFalse(badId.valid());
        assertFalse(badId.message().contains(operatorId));

        LoginValidation.Result badPassword = LoginValidation.check("nishat", 0, false);
        assertFalse(badPassword.valid());
        // there is no password material available to this class at all (it only ever sees a
        // length), so there is nothing to echo by construction.
    }
}
