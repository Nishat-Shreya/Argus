package com.argus.ui;

import com.argus.core.ApiKeyValue;

/** No javafx.* imports. Calls the core rule, does not restate it. */
final class ApiKeyValidation {

    private ApiKeyValidation() {
    }

    /**
     * @param keyText the raw PasswordField text
     * @return valid + null message, or invalid + a message that NEVER contains keyText
     */
    static Result check(String keyText) {
        String normalized = ApiKeyValue.normalize(keyText);
        if (normalized == null || normalized.isEmpty()) {
            return Result.error("a key is required");
        }
        if (normalized.length() > ApiKeyValue.MAX_LENGTH) {
            return Result.error("key exceeds " + ApiKeyValue.MAX_LENGTH + " characters");
        }
        if (!ApiKeyValue.isValid(keyText)) {
            return Result.error(
                    "key must be printable ASCII with no spaces or control characters");
        }
        return Result.ok();
    }

    /** Deliberately carries NO normalized key: no value object in this codebase holds key
     *  material, so there is nothing to accidentally toString(), log or put in an event. The
     *  caller normalizes with ApiKeyValue.normalize itself, on the FX thread, into a local. */
    record Result(boolean valid, String message) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result error(String message) {
            return new Result(false, message);
        }
    }
}
