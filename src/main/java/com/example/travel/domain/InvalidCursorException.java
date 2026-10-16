package com.example.travel.domain;

/**
 * 400 when a keyset cursor cannot be decoded: { error: "invalid_cursor" }.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
