package com.example.travel.api.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

/**
 * Uniform error body: { "error", "message", ...extra keys }.
 * Extras are flattened via {@link JsonAnyGetter}. The global snake_case naming
 * strategy does not apply to map keys, so callers pass them literally
 * ("existing_id", "existing_invoice_id", "ids", "max", ...).
 */
public class ApiError {

    private final String error;
    private final String message;
    private final Map<String, Object> details;

    public ApiError(String error, String message, Map<String, Object> details) {
        this.error = error;
        this.message = message;
        this.details = details == null ? Map.of() : details;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    @JsonAnyGetter
    public Map<String, Object> getDetails() {
        return details;
    }
}
