package com.example.travel.api.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body: { "error", "message", ...extra keys }.
 * Extras are flattened via {@link JsonAnyGetter} (existing_id, ids, max, ...).
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getDetails() {
        return details;
    }
}
