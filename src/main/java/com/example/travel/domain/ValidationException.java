package com.example.travel.domain;

import java.util.List;

/**
 * 400 with field-level messages: { error: "validation_error", message, errors: [{field, message}] }.
 */
public class ValidationException extends RuntimeException {

    public record FieldError(String field, String message) {
    }

    private final List<FieldError> errors;

    public ValidationException(String field, String message) {
        this(List.of(new FieldError(field, message)));
    }

    public ValidationException(List<FieldError> errors) {
        super(errors.isEmpty() ? "validation failed" : errors.getFirst().field() + ": " + errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
