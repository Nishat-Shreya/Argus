package com.argus.ui;

import com.argus.core.OperatorId;
import com.argus.core.VaultStore;

/**
 * Login-form validation. NO {@code javafx.*} imports -- so it is unit-testable headless today,
 * without the TestFX/Monocle decision P0-02 deferred to P1-06 (plan §7.10).
 *
 * The domain rules themselves stay in {@code core} ({@link OperatorId}, {@link VaultStore}) --
 * this class *calls* the rule, it does not restate it (the {@code DomainName} precedent,
 * P1-02 §3.1). It takes a password <em>length</em>, never the password itself, so it is
 * structurally impossible for this class (or a future log line inside it) to touch the secret.
 */
final class LoginValidation {

    private LoginValidation() {
    }

    /** @param passwordLength the length only -- this class never sees password material (§4.5) */
    static Result check(String operatorIdText, int passwordLength, boolean creatingNewVault) {
        if (operatorIdText == null || operatorIdText.isBlank()) {
            return Result.error("Operator id must not be blank.");
        }
        if (!OperatorId.isValid(operatorIdText)) {
            return Result.error("Operator id is invalid: use " + OperatorId.MIN_LENGTH + "-"
                    + OperatorId.MAX_LENGTH
                    + " lowercase letters, digits, '.', '_' or '-'.");
        }
        if (passwordLength <= 0) {
            return Result.error("Master password must not be blank.");
        }
        if (creatingNewVault && passwordLength < VaultStore.MIN_MASTER_PASSWORD_LENGTH) {
            return Result.error("Master password must be at least "
                    + VaultStore.MIN_MASTER_PASSWORD_LENGTH
                    + " characters when creating a new vault.");
        }
        return Result.ok(OperatorId.of(operatorIdText));
    }

    record Result(boolean valid, String message, OperatorId operatorId) {
        static Result ok(OperatorId id) {
            return new Result(true, null, id);
        }

        static Result error(String message) {
            return new Result(false, message, null);
        }
    }
}
