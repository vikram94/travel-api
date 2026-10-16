package com.example.travel.common;

import com.example.travel.domain.InvalidCursorException;
import com.example.travel.domain.ValidationException;

/**
 * Shared list-parameter rules: bounded page size (never an unbounded dump)
 * and keyset cursors (no OFFSET anywhere).
 */
public final class Pagination {

    private Pagination() {
    }

    /** Parses ?limit= with a default and hard max; anything else is a 400. */
    public static int parseLimit(String raw, int defaultValue, int maxValue, String parameter) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(parameter, "must be an integer");
        }
        if (value < 1 || value > maxValue) {
            throw new ValidationException(parameter, "must be between 1 and " + maxValue);
        }
        return value;
    }

    /** Decodes ?cursor=; absent -> first page, malformed -> 400 invalid_cursor. */
    public static Cursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Cursor.decode(raw)
                .orElseThrow(() -> new InvalidCursorException("cursor is not a valid keyset cursor"));
    }
}
