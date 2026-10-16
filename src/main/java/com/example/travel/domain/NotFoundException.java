package com.example.travel.domain;

import java.util.List;

/**
 * 404 for a single missing resource: { error: "not_found", message }.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
