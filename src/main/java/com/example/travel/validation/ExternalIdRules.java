package com.example.travel.validation;

import java.util.ArrayList;
import java.util.List;

import com.example.travel.domain.ValidationException.FieldError;

/**
 * external_id rules: case-sensitive, stored exactly as sent.
 * 400 if blank, has leading/trailing whitespace, contains control characters,
 * or exceeds 255 chars. No trim, no lowercase.
 */
public final class ExternalIdRules {

    public static final int MAX_LENGTH = 255;

    private ExternalIdRules() {
    }

    public static List<FieldError> check(String externalId, String field) {
        List<FieldError> errors = new ArrayList<>();
        if (externalId == null || externalId.isBlank()) {
            errors.add(new FieldError(field, "is required and must not be blank"));
            return errors;
        }
        if (!externalId.equals(externalId.strip())) {
            errors.add(new FieldError(field, "must not have leading or trailing whitespace"));
        }
        if (externalId.codePoints().anyMatch(Character::isISOControl)) {
            errors.add(new FieldError(field, "must not contain control characters"));
        }
        if (externalId.length() > MAX_LENGTH) {
            errors.add(new FieldError(field, "must be at most " + MAX_LENGTH + " characters"));
        }
        return errors;
    }
}
