package com.example.travel.validation;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.travel.domain.Money;
import com.example.travel.domain.ValidationException;
import com.example.travel.domain.ValidationException.FieldError;
import tools.jackson.databind.JsonNode;

/**
 * Small helpers shared by the per-type metadata validators. All of them
 * append field-level errors instead of throwing, so one request reports
 * every problem at once.
 */
final class MetadataRules {

    private MetadataRules() {
    }

    static String text(JsonNode parent, String name, String field, int maxLength, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull() || !node.isTextual()) {
            errors.add(new FieldError(field, "is required and must be a string"));
            return null;
        }
        String value = node.asText();
        if (value.isBlank()) {
            errors.add(new FieldError(field, "must not be blank"));
            return null;
        }
        if (value.length() > maxLength) {
            errors.add(new FieldError(field, "must be at most " + maxLength + " characters"));
            return null;
        }
        return value;
    }

    static String optionalText(JsonNode parent, String name, int maxLength, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(new FieldError("metadata." + name, "must be a string"));
            return null;
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            errors.add(new FieldError("metadata." + name, "must be at most " + maxLength + " characters"));
            return null;
        }
        return value;
    }

    /** Required ISO-8601 instant with offset, truncated to micros, stored as UTC. */
    static LocalDateTime instant(JsonNode parent, String name, String field, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull() || !node.isTextual()) {
            errors.add(new FieldError(field, "is required and must be an ISO-8601 timestamp"));
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText())
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, "must be an ISO-8601 timestamp with an offset (e.g. 2026-03-11T06:40:00Z)"));
            return null;
        }
    }

    /** Optional ISO date; accepts a plain date (2026-03-11) or a full timestamp. */
    static LocalDateTime date(JsonNode parent, String name, String field, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull() || !node.isTextual()) {
            errors.add(new FieldError(field, "is required and must be an ISO-8601 date"));
            return null;
        }
        String value = node.asText();
        try {
            return java.time.LocalDate.parse(value).atStartOfDay();
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(value)
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime()
                        .truncatedTo(ChronoUnit.MICROS);
            } catch (RuntimeException e2) {
                errors.add(new FieldError(field, "must be an ISO-8601 date (YYYY-MM-DD)"));
                return null;
            }
        }
    }

    /** Optional decimal amount in metadata; returns minor units or null. */
    static Long amount(JsonNode parent, String name, String field, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull()) {
            errors.add(new FieldError(field, "is required"));
            return null;
        }
        if (!node.isNumber()) {
            errors.add(new FieldError(field, "must be a number"));
            return null;
        }
        try {
            return Money.fromDecimal(node.decimalValue(), field).minor();
        } catch (ValidationException e) {
            e.errors().forEach(errors::add);
            return null;
        }
    }

    static JsonNode object(JsonNode parent, String name, String field, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull() || !node.isObject()) {
            errors.add(new FieldError(field, "is required and must be an object"));
            return null;
        }
        return node;
    }

    static JsonNode array(JsonNode parent, String name, String field, List<FieldError> errors) {
        JsonNode node = parent == null ? null : parent.get(name);
        if (node == null || node.isNull() || !node.isArray()) {
            errors.add(new FieldError(field, "is required and must be an array"));
            return null;
        }
        return node;
    }

}
