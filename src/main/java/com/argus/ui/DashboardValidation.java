package com.argus.ui;

import com.argus.core.DomainName;

/**
 * Target-field validation. NO {@code javafx.*} imports, so it is unit-testable headless — the
 * {@code LoginValidation} precedent (P1-05 §7.10), applied to the dashboard's target field.
 *
 * The rule itself lives in {@code core}: this class calls {@link DomainName#isValid} /
 * {@link DomainName#normalize}, exactly as that class's Javadoc promises (P1-02 §3.1). It does
 * not restate the rule.
 */
final class DashboardValidation {

    private DashboardValidation() {
    }

    static Result check(String targetText) {
        if (targetText == null || targetText.isBlank()) {
            return Result.error("Target domain must not be blank.");
        }
        if (!DomainName.isValid(targetText)) {
            return Result.error(
                    "Target domain is invalid: use two or more labels of letters, digits "
                            + "or hyphens, with non-ASCII names converted to punycode first.");
        }
        return Result.ok(DomainName.normalize(targetText));
    }

    record Result(boolean valid, String message, String target) {
        static Result ok(String normalizedTarget) {
            return new Result(true, null, normalizedTarget);
        }

        static Result error(String message) {
            return new Result(false, message, null);
        }
    }
}
